package com.animetracker.migration;

import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.MigrationVersion;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.Arrays;
import java.util.List;
import java.util.UUID;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 盯住 V11(站内通知)这段脚本本身, 不起 Spring 上下文, 直接调 Flyway。
 *
 * <p>形状与 {@link AdminActionLogMigrationTest} / {@link ReviewReplyMigrationTest} 一致,
 * 但它要说清的是**与 V9 相反的那个判断**: 账本一个外键都不挂, 而这张表挂四个, 其中两个
 * 还带 CASCADE。两张表的列几乎一一对应, 判断却完全相反 —— 账本记的是史料(必须活得比被
 * 记录的对象久), 通知是待办(对象没了这条通知就没有意义)。下一个人很可能把其中一处
 * 「统一」成另一处的样子, 所以这里两个方向都有人守着。
 *
 * <p>要验的东西按"错了会怎样"排:
 *
 * <ul>
 *   <li>级联少了 → 删一条评论会**报外键错误**, 整条删除路径坏掉(不是"没删干净");</li>
 *   <li>级联多了(给 recipient_id 也配上) → 删一个用户会静默带走别人收到的通知;</li>
 *   <li>索引列序反了 → 列表分页每次全表扫, 而名字上看不出区别。</li>
 * </ul>
 */
class NotificationMigrationTest {

    /** 每个用例一个全新的内存库, 免得用例之间互相看见对方的行 */
    private static String freshUrl() {
        return "jdbc:h2:mem:anitrack-v11-" + UUID.randomUUID().toString().replace("-", "")
                + ";DB_CLOSE_DELAY=-1;MODE=MySQL";
    }

    private static void migrate(String url, String target) {
        Flyway.configure()
                .dataSource(url, "sa", "")
                .locations("classpath:db/migration/h2")
                .target(MigrationVersion.fromVersion(target))
                .load()
                .migrate();
    }

    private static void execute(String url, String sql) throws SQLException {
        try (Connection c = DriverManager.getConnection(url, "sa", "");
             PreparedStatement ps = c.prepareStatement(sql)) {
            ps.executeUpdate();
        }
    }

    private static String string(String url, String sql) throws SQLException {
        try (Connection c = DriverManager.getConnection(url, "sa", "");
             PreparedStatement ps = c.prepareStatement(sql);
             ResultSet rs = ps.executeQuery()) {
            return rs.next() ? rs.getString(1) : null;
        }
    }

    private static List<String> strings(String url, String sql) throws SQLException {
        List<String> out = new java.util.ArrayList<>();
        try (Connection c = DriverManager.getConnection(url, "sa", "");
             PreparedStatement ps = c.prepareStatement(sql);
             ResultSet rs = ps.executeQuery()) {
            while (rs.next()) {
                out.add(rs.getString(1));
            }
        }
        return out;
    }

    private static int count(String url, String sql) throws SQLException {
        try (Connection c = DriverManager.getConnection(url, "sa", "");
             PreparedStatement ps = c.prepareStatement(sql);
             ResultSet rs = ps.executeQuery()) {
            rs.next();
            return rs.getInt(1);
        }
    }

    // ========== 造数据 ==========

    private static long seedUser(String url, String prefix) throws SQLException {
        String user = prefix + UUID.randomUUID().toString().substring(0, 12);
        execute(url, "INSERT INTO \"user\" (username, password, role, status) VALUES ('"
                + user + "', 'x', 'USER', 'ACTIVE')");
        return Long.parseLong(string(url, "SELECT id FROM \"user\" WHERE username = '" + user + "'"));
    }

    private static long seedReview(String url, long userId, int subjectId) throws SQLException {
        execute(url, "INSERT INTO review (user_id, subject_id, rating, content, created_at) "
                + "VALUES (" + userId + ", " + subjectId + ", 8, '还行', "
                + "TIMESTAMP '2030-01-01 00:00:00')");
        return Long.parseLong(string(url, "SELECT MAX(id) FROM review WHERE subject_id = " + subjectId));
    }

    private static long seedReply(String url, long reviewId, long userId) throws SQLException {
        execute(url, "INSERT INTO review_reply (review_id, user_id, content, created_at) "
                + "VALUES (" + reviewId + ", " + userId + ", '说得好', "
                + "TIMESTAMP '2030-01-03 00:00:00')");
        return Long.parseLong(
                string(url, "SELECT MAX(id) FROM review_reply WHERE review_id = " + reviewId));
    }

    /** 插一条通知. {@code replyId} 给 null 就是「赞评论」那类 */
    private static void seedNotification(String url, long recipientId, long actorId, String type,
                                         long reviewId, Long replyId) throws SQLException {
        execute(url, "INSERT INTO notification "
                + "(recipient_id, actor_id, type, review_id, reply_id, created_at) VALUES ("
                + recipientId + ", " + actorId + ", '" + type + "', " + reviewId + ", "
                + (replyId == null ? "NULL" : replyId) + ", TIMESTAMP '2030-01-05 00:00:00')");
    }

    // ========== 用例 ==========

    @Test
    @DisplayName("V11 能应用, 且八列的可空性与实体的 nullable 一致")
    void migrationCreatesTheTable() throws Exception {
        String url = freshUrl();

        migrate(url, "latest");

        assertThat(count(url, "SELECT COUNT(*) FROM INFORMATION_SCHEMA.TABLES "
                + "WHERE TABLE_NAME = 'NOTIFICATION'")).isEqualTo(1);

        /* 三列 NOT NULL 都有实体上的 nullable=false 对应(见 Notification.java)：
           少了它, 一行"没有收件人"的通知会写进库, 而读的时候 recipients 那侧炸在
           一个指不到库的报错上。 */
        for (String column : List.of("RECIPIENT_ID", "ACTOR_ID", "TYPE")) {
            assertThat(string(url, "SELECT IS_NULLABLE FROM INFORMATION_SCHEMA.COLUMNS "
                    + "WHERE TABLE_NAME = 'NOTIFICATION' AND COLUMN_NAME = '" + column + "'"))
                    .as("%s 必须是 NOT NULL", column)
                    .isEqualTo("NO");
        }
        /* 四列可空, 每一列的"可空"都是一条语义:
           · review_id    —— 今天三种类型都写它, 留着可空是为了不把类型集合焊死在这一版;
           · reply_id     —— 赞评论那条**本来就没有**回复;
           · read_at      —— 未读就是 NULL, 这是它与"已读"之间唯一的区别;
           · created_at   —— 与 review 那张表同一个口径(应用侧写值). */
        for (String column : List.of("REVIEW_ID", "REPLY_ID", "READ_AT", "CREATED_AT")) {
            assertThat(string(url, "SELECT IS_NULLABLE FROM INFORMATION_SCHEMA.COLUMNS "
                    + "WHERE TABLE_NAME = 'NOTIFICATION' AND COLUMN_NAME = '" + column + "'"))
                    .as("%s 允许为空", column)
                    .isEqualTo("YES");
        }
    }

    /**
     * <b>四个外键都在, 但只有两个带 CASCADE —— 这是这张表最要紧的一条线。</b>
     *
     * <p>先断"四个都在": 少了 recipient/actor 那两个, 写入就拦不住一个不存在的用户,
     * 而那是 V9 的账本**刻意接受**的代价、这张表刻意不要的。
     *
     * <p>再断"哪两个带 CASCADE": {@code review_id}/{@code reply_id} 必须 CASCADE(理由
     * 见 V11 头部), 而 {@code recipient_id}/{@code actor_id} **必须不级联** —— 给它们
     * 配上 CASCADE 会带来一个安静得多、也更坏的后果: 删一个用户时, 别人收到的、由他
     * 触发的通知会一起消失。这条断言就是那个方向的哨兵。
     */
    @Test
    @DisplayName("四个外键: review/reply 带 CASCADE, recipient/actor 不带")
    void theForeignKeysCascadeInExactlyOneDirection() throws Exception {
        String url = freshUrl();

        migrate(url, "latest");

        assertThat(strings(url, "SELECT CONSTRAINT_NAME FROM INFORMATION_SCHEMA.TABLE_CONSTRAINTS "
                + "WHERE TABLE_NAME = 'NOTIFICATION' AND CONSTRAINT_TYPE = 'FOREIGN KEY' "
                + "ORDER BY CONSTRAINT_NAME"))
                .as("对照: 这条查询本身认得出外键, 所以下面那几条才有意义")
                .containsExactlyInAnyOrder("FK_NOTIFICATION_RECIPIENT", "FK_NOTIFICATION_ACTOR",
                        "FK_NOTIFICATION_REVIEW", "FK_NOTIFICATION_REPLY");

        for (String fk : List.of("FK_NOTIFICATION_REVIEW", "FK_NOTIFICATION_REPLY")) {
            assertThat(rule(url, fk)).as("%s 必须是 CASCADE", fk).isEqualTo("CASCADE");
        }
        for (String fk : List.of("FK_NOTIFICATION_RECIPIENT", "FK_NOTIFICATION_ACTOR")) {
            assertThat(rule(url, fk))
                    .as("%s 不能级联: 否则删一个用户会顺手抹掉别人收到的通知", fk)
                    .isNotEqualTo("CASCADE");
        }
    }

    private static String rule(String url, String fk) throws SQLException {
        return string(url, "SELECT DELETE_RULE FROM INFORMATION_SCHEMA.REFERENTIAL_CONSTRAINTS "
                + "WHERE CONSTRAINT_NAME = '" + fk + "'");
    }

    /**
     * <b>两级级联: 删一条评论, 三类通知一起消失。</b>
     *
     * <p>第一级是 {@code fk_notification_review}(评论本身带走 REPLY 与 REVIEW_LIKE),
     * 第二级是 {@code fk_review_reply_review}(V8)接着 {@code fk_notification_reply}
     * (本迁移)把 REPLY_LIKE 也带走 —— 中间那一跳发生在删回复的时候, 而删回复是级联
     * 触发的, 不是任何一行 Java 发出来的。
     *
     * <p>第二级漏配的表现是<b>删一条有回复的评论直接报外键错误</b>(剩下的那条约束是
     * RESTRICT), 而不是"少删了几行"。只验第一级抓不到它: 那条评论下没有回复。
     */
    @Test
    @DisplayName("删评论: 指向它的三类通知全部消失, 包括挂在它回复上的那一类(两级级联)")
    void deletingAReviewTakesAllThreeKindsWithIt() throws Exception {
        String url = freshUrl();
        migrate(url, "latest");
        long author = seedUser(url, "a");
        long actor = seedUser(url, "b");
        long reviewId = seedReview(url, author, 96000301);
        long replyId = seedReply(url, reviewId, author);
        // 三类各一条, 收件人都是 author
        seedNotification(url, author, actor, "REPLY", reviewId, replyId);
        seedNotification(url, author, actor, "REVIEW_LIKE", reviewId, null);
        seedNotification(url, author, actor, "REPLY_LIKE", reviewId, replyId);

        // 另一条评论上的一条通知: 它必须活下来, 否则"全删光"也能让上面那三条变绿
        long otherReview = seedReview(url, author, 96000302);
        seedNotification(url, author, actor, "REVIEW_LIKE", otherReview, null);

        assertThat(count(url, "SELECT COUNT(*) FROM notification")).isEqualTo(4);

        execute(url, "DELETE FROM review WHERE id = " + reviewId);

        assertThat(count(url, "SELECT COUNT(*) FROM notification WHERE review_id = " + reviewId))
                .as("第一级: 指向这条评论的两类").isZero();
        assertThat(count(url, "SELECT COUNT(*) FROM notification WHERE reply_id = " + replyId))
                .as("第二级: 挂在它回复上的那一类。漏配的话这一句 DELETE 直接报外键错误")
                .isZero();
        assertThat(count(url, "SELECT COUNT(*) FROM notification"))
                .as("别人那条评论上的通知不能跟着消失").isEqualTo(1);
    }

    /**
     * 删一条**单独的回复**(楼还在): 只有它自己那一类通知消失。
     *
     * <p>这是 {@code ReviewReplyService.deleteReply} 打的东西, 也是用户侧唯一能删掉的
     * 中间层。它与上一条是两条不同的路径: 上一条从顶往下带, 这一条从中间抽走一层,
     * 而"这条回复所属的那条评论"必须毫发无损。
     */
    @Test
    @DisplayName("删一条回复: 只有它那条 REPLY_LIKE 消失, 同一楼的其它通知不受影响")
    void deletingOneReplyTakesOnlyItsOwnNotification() throws Exception {
        String url = freshUrl();
        migrate(url, "latest");
        long author = seedUser(url, "a");
        long actor = seedUser(url, "b");
        long reviewId = seedReview(url, author, 96000303);
        long doomed = seedReply(url, reviewId, author);
        long kept = seedReply(url, reviewId, author);
        seedNotification(url, author, actor, "REPLY_LIKE", reviewId, doomed);
        seedNotification(url, author, actor, "REPLY_LIKE", reviewId, kept);
        seedNotification(url, author, actor, "REPLY", reviewId, kept);

        execute(url, "DELETE FROM review_reply WHERE id = " + doomed);

        assertThat(count(url, "SELECT COUNT(*) FROM notification WHERE reply_id = " + doomed))
                .as("没有级联的话这一句 DELETE 直接报外键错误").isZero();
        assertThat(count(url, "SELECT COUNT(*) FROM notification"))
                .as("删的是一条回复, 不是整楼").isEqualTo(2);
    }

    /**
     * <b>删一个用户会被拒绝</b> —— 两个 user 外键都不级联的直接后果。
     *
     * <p>把它写下来是因为它看着像缺陷: 本仓目前没有删用户这条路, 所以这条约束今天只
     * 是个数据完整性声明。但下一个人加「注销账号」时会在这一步撞上它, 而那时正确的
     * 做法是<b>先决定这些通知怎么办</b>(导出? 一起删?), 不是在迁移里补一个 CASCADE
     * 让错误消失 —— 补上之后, 一个用户注销会静默清掉别人收件箱里的历史。
     */
    @Test
    @DisplayName("删用户被外键挡住 —— 这是刻意的, 不是漏了 CASCADE")
    void deletingAUserIsBlocked() throws Exception {
        String url = freshUrl();
        migrate(url, "latest");
        long author = seedUser(url, "a");
        long actor = seedUser(url, "b");
        long reviewId = seedReview(url, author, 96000304);
        seedNotification(url, author, actor, "REVIEW_LIKE", reviewId, null);

        assertThatThrownBy(() -> execute(url, "DELETE FROM \"user\" WHERE id = " + actor))
                .as("actor 侧被挡").isInstanceOf(SQLException.class);
        assertThatThrownBy(() -> execute(url, "DELETE FROM \"user\" WHERE id = " + author))
                .as("recipient 侧同样被挡").isInstanceOf(SQLException.class);
        assertThat(count(url, "SELECT COUNT(*) FROM notification"))
                .as("两次都失败, 通知一行都不能少").isEqualTo(1);
    }

    /**
     * {@code read_at IS NULL} 就是未读, 而且它能原样存取一个时间。
     *
     * <p>这一列同时回答"读没读"和"什么时候读的" —— 后者是"按已读时间排序""展示上次
     * 查看时间"唯一的来源(V11 头部写了为什么不用布尔列)。所以这里两件事都要验:
     * NULL 存得进去、时间也存得进去。
     */
    @Test
    @DisplayName("read_at: NULL 是未读, 写进去的时间能原样读回来")
    void readAtRoundTrips() throws Exception {
        String url = freshUrl();
        migrate(url, "latest");
        long author = seedUser(url, "a");
        long actor = seedUser(url, "b");
        long reviewId = seedReview(url, author, 96000305);
        seedNotification(url, author, actor, "REPLY", reviewId, null);

        assertThat(string(url, "SELECT read_at FROM notification"))
                .as("刚写进来的通知必须是未读").isNull();

        execute(url, "UPDATE notification SET read_at = TIMESTAMP '2030-02-01 12:34:56.789'");

        assertThat(string(url, "SELECT read_at FROM notification")).startsWith("2030-02-01 12:34:56.789");
    }

    /**
     * {@code (recipient_id, created_at)} 这棵树服务两件事, 而**列序**是它成立的全部。
     *
     * <p>列表分页是 {@code WHERE recipient_id = ? ORDER BY created_at DESC} —— 正是它;
     * 未读计数是 {@code WHERE recipient_id = ? AND read_at IS NULL}, 靠它的最左前缀
     * (V11 头部写了为什么不给未读单独建一棵)。
     *
     * <p>{@code (created_at, recipient_id)} 也是一棵合法、也能建出来的索引, 但两个查询
     * 一个都用不上 —— 而名字上看不出区别, 所以这里断的是列序。
     */
    @Test
    @DisplayName("V11 建出 idx_notification_recipient_created, 列序是 (recipient_id, created_at)")
    void theListIndexHasTheRightColumnsInTheRightOrder() throws Exception {
        String url = freshUrl();

        migrate(url, "latest");

        assertThat(strings(url, "SELECT COLUMN_NAME FROM INFORMATION_SCHEMA.INDEX_COLUMNS "
                + "WHERE TABLE_NAME = 'NOTIFICATION' "
                + "AND INDEX_NAME = 'IDX_NOTIFICATION_RECIPIENT_CREATED' ORDER BY ORDINAL_POSITION"))
                .as("先按收件人定位, 再按时间取序 —— 反过来这棵索引就白建了")
                .containsExactly("RECIPIENT_ID", "CREATED_AT");
        // 未读计数刻意没有自己的索引: 单独一棵 (recipient_id, read_at) 是给会长大的树
        // 付维护成本, 而最左前缀已经把它收窄到"一个人的几十行"。
        // 这里问的是"有没有任何一棵索引带 read_at 这一列" —— 比"非主键索引恰好只有
        // 一棵"精确: H2 会给每个外键自动建一棵 {FK}_INDEX_A, 那种断言会红在一个与
        // 本意无关的地方。
        assertThat(strings(url, "SELECT DISTINCT INDEX_NAME FROM INFORMATION_SCHEMA.INDEX_COLUMNS "
                + "WHERE TABLE_NAME = 'NOTIFICATION' AND COLUMN_NAME = 'READ_AT'"))
                .as("未读那半靠最左前缀, 不该有第二棵树")
                .isEmpty();
    }

    /**
     * V11 的两份脚本必须是同一批语句。
     *
     * <p>与 V4~V10 同一条规矩(见 {@link HotPathIndexMigrationTest}): 只改一份的后果是
     * H2 环境全绿、生产 PG 要到部署那一刻才发现少了一张表。这条是「PG 那边也应该没问题」
     * 这句话唯一能拿出来的证据。
     */
    @Test
    @DisplayName("V11 的 h2 与 postgres 两份脚本: 去掉注释与格式差异后, 语句完全相同")
    void bothDialectsRunTheSameStatements() throws Exception {
        assertThat(statementsOf("postgres"))
                .as("V11 两份脚本的语句必须一致; 只改一份的话 H2 环境是绿的, 生产要到部署时才发现")
                .isEqualTo(statementsOf("h2"))
                .isNotEmpty();
    }

    /** 脚本里的语句. 去掉 -- 注释、把连续空白折成一个空格、按分号切开, 免得被换行方式绊住 */
    private static List<String> statementsOf(String dialect) throws IOException {
        Path file = Path.of("src", "main", "resources", "db", "migration", dialect,
                "V11__add_notifications.sql");
        String sql = Files.readString(file).replaceAll("(?m)--.*$", "");
        return Arrays.stream(sql.replaceAll("\\s+", " ").split(";"))
                .map(String::trim)
                .filter(s -> !s.isEmpty())
                .collect(Collectors.toList());
    }
}
