package com.animetracker.service;

import com.animetracker.entity.User;
import com.animetracker.util.TextSnippet;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;

import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 通知的**读侧**: 列表分页 / 未读计数 / 标记已读。
 *
 * <p><b>为什么这一层要在真库上跑</b>(它的三个被测对象都是 JPQL, 替身验不了):
 *
 * <ul>
 *   <li>{@code findPage} 里那两处 {@code LEFT JOIN FETCH} —— 写成 INNER 会把整整一类
 *       通知("赞了我的评论"没有 reply)从列表里静默抹掉, 而返回的其它行一个字节都不差;</li>
 *   <li>{@code JOIN FETCH n.actor} —— 这个 service 刻意不带类级事务, 不 fetch 的话
 *       {@code toRow} 读 {@code actor.getUsername()} 会炸在懒加载上;</li>
 *   <li>{@code markAllRead} 里的 {@code AND n.readAt IS NULL} —— 幂等的全部依据就在这
 *       一句里, 少了它第二次调用会把第一次的"什么时候读的"一起改写。</li>
 * </ul>
 *
 * <p>数据全部用 {@code JdbcTemplate} 直接写: 这个类要验的是"读", 用接口造数据等于把
 * 写入侧(它的用例在 {@link NotificationWriteTest})也拖进来, 一处失败会红两处。
 * 传进去的 {@code User} 只是个带 id 的壳 —— 三条读法都只问 {@code user.getId()}。
 */
@SpringBootTest(properties = {
        "spring.datasource.url=jdbc:h2:mem:anitrack-notification-read;DB_CLOSE_DELAY=-1;MODE=MySQL",
        "anitrack.preload.enabled=false"
})
@ActiveProfiles("dev")
class NotificationReadTest {

    /** 每个用例自己的番剧 id 段, 免得用例之间互相看见对方的行 */
    private static final int SUBJECT_PAGING = 960101;
    private static final int SUBJECT_SHAPE = 960102;
    private static final int SUBJECT_OTHER = 960103;
    private static final int SUBJECT_CASCADE = 960104;

    @Autowired
    private NotificationService notificationService;

    @Autowired
    private JdbcTemplate jdbc;

    /** 一个带 id 的壳 —— 读路径只问 {@code getId()} */
    private static User shell(long id) {
        return User.builder().id(id).build();
    }

    /** 行是 {@code Map<String,Object>} —— 转型集中在这里一处, 免得断言里全是 {@code Map<?, ?>} */
    @SuppressWarnings("unchecked")
    private static Map<String, Object> row(Object raw) {
        return (Map<String, Object>) raw;
    }

    /** 列表里第 {@code i} 行 */
    private static Map<String, Object> row(Map<String, Object> page, int i) {
        return row(((List<?>) page.get("list")).get(i));
    }

    // ========== 造数据(直接写库) ==========

    private long seedUser(String prefix) {
        String username = prefix + UUID.randomUUID().toString().substring(0, 8);
        jdbc.update("INSERT INTO \"user\" (username, password, role, status) "
                + "VALUES (?, 'x', 'USER', 'ACTIVE')", username);
        return jdbc.queryForObject("SELECT id FROM \"user\" WHERE username = ?", Long.class, username);
    }

    private long seedReview(long userId, int subjectId, String content) {
        jdbc.update("INSERT INTO review (user_id, subject_id, rating, content, created_at) "
                + "VALUES (?, ?, 8, ?, TIMESTAMP '2030-01-01 00:00:00')", userId, subjectId, content);
        return jdbc.queryForObject(
                "SELECT MAX(id) FROM review WHERE subject_id = ?", Long.class, subjectId);
    }

    private long seedReply(long reviewId, long userId, String content) {
        jdbc.update("INSERT INTO review_reply (review_id, user_id, content, created_at) "
                + "VALUES (?, ?, ?, TIMESTAMP '2030-01-02 00:00:00')", reviewId, userId, content);
        return jdbc.queryForObject(
                "SELECT MAX(id) FROM review_reply WHERE review_id = ?", Long.class, reviewId);
    }

    /** 插一条通知. {@code createdAt} 传 SQL 里的时间字面量, 用来控制排序 */
    private void seedNotification(long recipientId, long actorId, String type, long reviewId,
                                  Long replyId, String createdAt, String readAt) {
        jdbc.update("INSERT INTO notification "
                        + "(recipient_id, actor_id, type, review_id, reply_id, created_at, read_at) "
                        + "VALUES (?, ?, ?, ?, ?, " + createdAt + ", " + readAt + ")",
                recipientId, actorId, type, reviewId, replyId);
    }

    // ========== 用例 ==========

    /**
     * <b>分页信封 + 稳定序。</b>
     *
     * <p>{@code created_at} 全部写成同一个值, 于是顺序<b>只剩</b>{@code id DESC} 这一个
     * 依据 —— 这正是要钉住的那条: {@code created_at} 是应用侧 ({@code @PrePersist}) 写的,
     * 同一毫秒落两行完全可能, 没有兜底键时翻页会重复或丢行(第 1 页和第 2 页出现同一行,
     * 或者某一行谁都没见过)。把时间戳拉平, 这条断言才真的在验那个兜底键。
     */
    @Test
    @DisplayName("分页: 信封是 {list,total,page}, 时间戳全相同时靠 id 兜底, 两页不重不漏")
    void pagingIsStableEvenWhenTimestampsTie() {
        long me = seedUser("pgme");
        long actor = seedUser("pgactor");
        long reviewId = seedReview(me, SUBJECT_PAGING, "分页用");
        for (int i = 0; i < 25; i++) {
            seedNotification(me, actor, "REVIEW_LIKE", reviewId, null,
                    "TIMESTAMP '2030-01-01 00:00:00'", "NULL");
        }

        Map<String, Object> first = notificationService.getNotificationPage(shell(me), 1, 20);
        assertThat(first.get("total")).isEqualTo(25);
        assertThat(first.get("page")).isEqualTo(1);
        List<?> page1 = (List<?>) first.get("list");
        assertThat(page1).hasSize(20);

        Map<String, Object> second = notificationService.getNotificationPage(shell(me), 2, 20);
        assertThat(second.get("total")).as("每一页都报同一个 total").isEqualTo(25);
        List<?> page2 = (List<?>) second.get("list");
        assertThat(page2).hasSize(5);

        Set<Object> ids = new HashSet<>();
        for (Object row : page1) {
            ids.add(((Map<?, ?>) row).get("id"));
        }
        assertThat(ids).as("同一页里不能有重复行").hasSize(20);
        for (Object row : page2) {
            assertThat(ids.add(((Map<?, ?>) row).get("id")))
                    .as("第 2 页不能与第 1 页重复 —— 时间戳撞上时靠 id 兜底")
                    .isTrue();
        }

        // 最新的在最上面: 第 1 页的第一个 id 必须比第 2 页最后一个大
        assertThat((Long) ((Map<?, ?>) page1.get(0)).get("id"))
                .isGreaterThan((Long) ((Map<?, ?>) page2.get(4)).get("id"));
    }

    /**
     * 越界页报**真实的 total** 与一个空列表, 而不是 <code>total=0</code>。
     *
     * <p>报 0 的话前端会把翻页控件从 2 页缩成 1 页 —— 用户点了一下"下一页", 控件自己
     * 消失了。这个行为与 {@code AdminService.getUserPage} / {@code getActionPage} 一致。
     */
    @Test
    @DisplayName("越界页: 列表为空, 但 total 仍然是真的")
    void anOutOfRangePageStillReportsTheRealTotal() {
        long me = seedUser("oor");
        long actor = seedUser("ooractor");
        long reviewId = seedReview(me, SUBJECT_SHAPE, "越界用");
        seedNotification(me, actor, "REPLY", reviewId, seedReply(reviewId, me, "回一句"),
                "TIMESTAMP '2030-01-01 00:00:00'", "NULL");

        Map<String, Object> page = notificationService.getNotificationPage(shell(me), 99, 20);

        assertThat((List<?>) page.get("list")).isEmpty();
        assertThat(page.get("total")).as("缩成 0 的话翻页控件会自己消失").isEqualTo(1);
        assertThat(page.get("page")).isEqualTo(99);
    }

    /** {@code page} / {@code limit} 这一层也夹一次, 不假设自己只被 controller 调用 */
    @Test
    @DisplayName("page 0 当 1; limit 越小越用默认值, 越大夹到 50")
    void pageAndLimitAreClamped() {
        long me = seedUser("clamp");
        long actor = seedUser("clampactor");
        long reviewId = seedReview(me, SUBJECT_OTHER, "夹取用");
        for (int i = 0; i < 60; i++) {
            seedNotification(me, actor, "REVIEW_LIKE", reviewId, null,
                    "TIMESTAMP '2030-01-01 00:00:00'", "NULL");
        }

        Map<String, Object> clampedPage = notificationService.getNotificationPage(shell(me), 0, 10);
        assertThat(clampedPage.get("page")).as("页码从 1 开始").isEqualTo(1);

        assertThat((List<?>) notificationService.getNotificationPage(shell(me), 1, 0).get("list"))
                .as("limit 0 用默认的 20, 不是空列表").hasSize(20);
        assertThat((List<?>) notificationService.getNotificationPage(shell(me), 1, -5).get("list"))
                .as("负数同理").hasSize(20);
        assertThat((List<?>) notificationService.getNotificationPage(shell(me), 1, 999).get("list"))
                .as("上限 50 与评论列表同量级").hasSize(50);
    }

    /**
     * <b>一行的形状, 三种类型各一个。</b>
     *
     * <p>两条只有在这一层测得出来的:
     *
     * <ul>
     *   <li><b>「赞了我的评论」这一类必须出现在列表里</b> —— 它的 {@code reply_id} 是
     *       NULL, 而 {@code findPage} 那句写成 INNER JOIN 就会把整类抹掉,
     *       其它行的内容一个字节都不差;</li>
     *   <li><b>摘要是 60 字</b> —— 评论正文最长 5000 字, 一页 20 行原样带出去就是几十 KB。</li>
     * </ul>
     */
    @Test
    @DisplayName("行的形状: 三类都在; 评论正文截到 60 字; 只打分不写字的那条摘要为 null")
    void everyRowCarriesWhatTheListNeeds() {
        long me = seedUser("shape");
        long actor = seedUser("shapeactor");
        String longText = "甲".repeat(200);
        long reviewId = seedReview(me, SUBJECT_CASCADE, longText);
        long replyId = seedReply(reviewId, me, "我那句回复");
        seedNotification(me, actor, "REPLY", reviewId, replyId,
                "TIMESTAMP '2030-01-03 00:00:00'", "NULL");
        seedNotification(me, actor, "REVIEW_LIKE", reviewId, null,
                "TIMESTAMP '2030-01-02 00:00:00'", "TIMESTAMP '2030-01-04 00:00:00'");
        // 只打分不写字的那种评论: 摘要是 null 而不是空串(前端据此显示「（无文字）」)
        long silent = seedReview(me, SUBJECT_CASCADE + 1, null);
        seedNotification(me, actor, "REPLY_LIKE", silent, replyId,
                "TIMESTAMP '2030-01-01 00:00:00'", "NULL");

        Map<String, Object> result = notificationService.getNotificationPage(shell(me), 1, 20);
        assertThat((List<?>) result.get("list")).as("三类都必须出现, 一类都不能被 JOIN 吃掉")
                .hasSize(3);

        Map<String, Object> reply = row(result, 0);
        assertThat(reply.get("type")).isEqualTo("REPLY");
        assertThat(reply.get("actorName")).as("JOIN FETCH actor: 不 fetch 这里会炸在懒加载上")
                .isEqualTo(usernameOf(actor));
        assertThat(reply).containsKey("actorAvatar");
        assertThat(reply.get("subjectId")).isEqualTo(SUBJECT_CASCADE);
        assertThat(reply.get("reviewId")).isEqualTo(reviewId);
        assertThat(reply.get("replyId")).isEqualTo(replyId);
        // 200 字的正文截成 60 字 + 省略号, 用常量而不是 61 这个魔法数
        assertThat((String) reply.get("reviewContent"))
                .hasSize(TextSnippet.LENGTH + 1).endsWith("…");
        assertThat(reply.get("replyContent")).isEqualTo("我那句回复");
        assertThat(reply.get("read")).as("read_at 为 null 就是未读").isEqualTo(false);
        assertThat(reply.get("createdAt")).isNotNull();

        Map<String, Object> like = row(result, 1);
        assertThat(like.get("type")).isEqualTo("REVIEW_LIKE");
        assertThat(like.get("replyId")).as("赞评论那条本来就没有回复").isNull();
        assertThat(like.get("replyContent")).isNull();
        assertThat(like.get("read")).as("这条已经读过了").isEqualTo(true);

        Map<String, Object> silentRow = row(result, 2);
        assertThat(silentRow.get("reviewContent"))
                .as("只打分不写字的评论: null 而不是空串").isNull();
        assertThat(silentRow.get("replyContent")).isEqualTo("我那句回复");
    }

    /** 列表与未读计数都只看自己那几行 —— 这一页上任何一条"忘了带收件人"的查询都是越权读 */
    @Test
    @DisplayName("只看得到自己的: 别人的通知既不在列表里, 也不进我的未读计数")
    void onlyMyOwnNotificationsAreVisible() {
        long me = seedUser("mine");
        long other = seedUser("theirs");
        long actor = seedUser("sharedactor");
        long reviewId = seedReview(me, SUBJECT_PAGING + 50, "各看各的");
        seedNotification(me, actor, "REPLY", reviewId, seedReply(reviewId, me, "给我"),
                "TIMESTAMP '2030-01-01 00:00:00'", "NULL");
        seedNotification(other, actor, "REPLY", reviewId, null,
                "TIMESTAMP '2030-01-01 00:00:00'", "NULL");
        seedNotification(other, actor, "REVIEW_LIKE", reviewId, null,
                "TIMESTAMP '2030-01-01 00:00:00'", "NULL");

        assertThat(notificationService.getNotificationPage(shell(me), 1, 20).get("total"))
                .as("列表的 total 是我的").isEqualTo(1);
        assertThat(notificationService.getUnreadCount(shell(me))).isEqualTo(1L);
        assertThat(notificationService.getUnreadCount(shell(other))).isEqualTo(2L);
    }

    @Test
    @DisplayName("未读计数: 只数 read_at 为空的那几条")
    void unreadCountIgnoresWhatIsAlreadyRead() {
        long me = seedUser("unread");
        long actor = seedUser("unreadactor");
        long reviewId = seedReview(me, SUBJECT_PAGING + 51, "未读用");
        seedNotification(me, actor, "REVIEW_LIKE", reviewId, null,
                "TIMESTAMP '2030-01-01 00:00:00'", "NULL");
        seedNotification(me, actor, "REVIEW_LIKE", reviewId, null,
                "TIMESTAMP '2030-01-02 00:00:00'", "TIMESTAMP '2030-01-05 00:00:00'");
        seedNotification(me, actor, "REVIEW_LIKE", reviewId, null,
                "TIMESTAMP '2030-01-03 00:00:00'", "NULL");

        assertThat(notificationService.getUnreadCount(shell(me)))
                .as("三条里两条没读过").isEqualTo(2L);
    }

    /**
     * <b>标记已读是幂等的, 而且第二次不会改写第一次的时间。</b>
     *
     * <p>两半都要验: 只验"未读归零"的话, 一句没有 {@code AND readAt IS NULL} 的
     * {@code UPDATE} 也能过 —— 而它每次打开页面都会把所有历史通知的"什么时候读的"
     * 刷成现在, 于是"按已读时间排序"这件事再也做不了。
     */
    @Test
    @DisplayName("标记已读: 改掉自己的全部未读, 重复调用改 0 行且时间戳不动")
    void markingAllReadIsIdempotent() {
        long me = seedUser("mark");
        long other = seedUser("markother");
        long actor = seedUser("markactor");
        long reviewId = seedReview(me, SUBJECT_PAGING + 52, "标已读用");
        seedNotification(me, actor, "REPLY", reviewId, null,
                "TIMESTAMP '2030-01-01 00:00:00'", "NULL");
        seedNotification(me, actor, "REVIEW_LIKE", reviewId, null,
                "TIMESTAMP '2030-01-02 00:00:00'", "NULL");
        seedNotification(other, actor, "REPLY", reviewId, null,
                "TIMESTAMP '2030-01-01 00:00:00'", "NULL");

        assertThat(notificationService.markAllRead(shell(me))).as("两条未读").isEqualTo(2);
        assertThat(notificationService.getUnreadCount(shell(me))).isZero();

        String firstStamp = jdbc.queryForObject(
                "SELECT MIN(read_at) FROM notification WHERE recipient_id = ?", String.class, me);

        assertThat(notificationService.markAllRead(shell(me)))
                .as("第二次一行都不该被改 —— 守卫在 AND readAt IS NULL 上").isZero();
        assertThat(jdbc.queryForObject(
                "SELECT MIN(read_at) FROM notification WHERE recipient_id = ?", String.class, me))
                .as("'什么时候读的'不能被第二次调用改写").isEqualTo(firstStamp);
        assertThat(notificationService.getUnreadCount(shell(other)))
                .as("别人的未读不能被我清掉").isEqualTo(1L);
    }

    /**
     * <b>删掉一条评论之后, 它下面三类通知都从列表里消失 —— 靠的是库级联, 读路径不做任何过滤。</b>
     *
     * <p>这是那张表"挂外键"这个决定在可观测层面的样子。读路径刻意不写 {@code WHERE
     * review_id IN (...)} 之类的过滤(级联已经保证了没有孤儿), 所以只要级联少了,
     * 列表里就会留下一条 {@code subjectId} 为 null 的行 —— 用户点进去不知道去哪,
     * 而它不会报错, 只会一直挂在那里。
     *
     * <p>这里删的是评论、表里挂的是"评论 + 回复"两层通知, 于是顺带把两级级联在
     * **读出来**这一侧再验一次(库那一侧的断言在 {@code NotificationMigrationTest})。
     */
    @Test
    @DisplayName("删评论后: 三类通知都不在列表里了, 未读计数也跟着降")
    void deletingAReviewClearsTheListWithoutAnyFilter() {
        long me = seedUser("cascade");
        long actor = seedUser("cascadeactor");
        long reviewId = seedReview(me, SUBJECT_OTHER + 50, "级联用");
        long replyId = seedReply(reviewId, me, "会被带走的回复");
        seedNotification(me, actor, "REPLY", reviewId, replyId,
                "TIMESTAMP '2030-01-01 00:00:00'", "NULL");
        seedNotification(me, actor, "REVIEW_LIKE", reviewId, null,
                "TIMESTAMP '2030-01-02 00:00:00'", "NULL");
        seedNotification(me, actor, "REPLY_LIKE", reviewId, replyId,
                "TIMESTAMP '2030-01-03 00:00:00'", "NULL");
        // 另一条评论上的通知: 它必须活下来, 否则"全删光"也能让下面那几条变绿
        long kept = seedReview(me, SUBJECT_OTHER + 51, "没关系的一条");
        seedNotification(me, actor, "REVIEW_LIKE", kept, null,
                "TIMESTAMP '2030-01-04 00:00:00'", "NULL");

        assertThat(notificationService.getNotificationPage(shell(me), 1, 20).get("total"))
                .as("先确认四条真在, 否则下面那条断言是空过").isEqualTo(4);

        // 与 AdminService 手写 SQL 删评论时打在库上的是同一句: 绕过全部 Java 代码
        jdbc.update("DELETE FROM review WHERE id = ?", reviewId);

        Map<String, Object> after = notificationService.getNotificationPage(shell(me), 1, 20);
        assertThat(after.get("total")).as("只剩别人那条评论上的").isEqualTo(1);
        assertThat(row(after, 0).get("reviewId")).isEqualTo(kept);
        assertThat(notificationService.getUnreadCount(shell(me)))
                .as("被级联带走的未读不能继续占着红点").isEqualTo(1L);
    }

    /** 用例之间互相看得见对方的行(同一个库), 所以断言只落在自己造的那些 id 上 —— 这里只是把用户名捞回来 */
    private String usernameOf(long userId) {
        return jdbc.queryForObject("SELECT username FROM \"user\" WHERE id = ?", String.class, userId);
    }

    @BeforeEach
    void reset() {
        // 没有 @Transactional(与其它集成测试同一个理由: 网页请求本来就没有外层事务),
        // 所以每个用例自己清干净。删表的顺序按外键来 —— 通知指向 review/reply。
        jdbc.update("DELETE FROM notification");
        jdbc.update("DELETE FROM reply_like");
        jdbc.update("DELETE FROM review_reply");
        jdbc.update("DELETE FROM review_like");
        jdbc.update("DELETE FROM review");
    }
}
