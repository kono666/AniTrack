package com.animetracker.service;

import com.animetracker.dto.RequestDTO.ReviewRequest;
import com.animetracker.dto.RequestDTO.TrackRequest;
import com.animetracker.entity.User;
import com.animetracker.repository.UserRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;

import java.time.LocalDateTime;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 「同一用户对同一部番只能有一条」这件事, 在真实数据库上的验证.
 *
 * <p>和 {@link UniqueConstraintMigrationTest} 的分工: 那个类验的是迁移脚本本身
 * (在裸 H2 上跑 SQL), 这个类验的是**跑起来的应用**——实体注解与迁移脚本有没有对上、
 * 服务层的「先查后写」在重复提交下会不会加出第二行. 两者都必要: 约束建对了但应用层
 * 每次插入都插新的, 用户看到的仍然是列表里两条一样的番; 应用层写得再小心,
 * 没有约束兜底, 竞态窗口里照样能落两行.
 *
 * <p><b>这里刻意不用 @SpyBean 去伪造「查不到」</b>
 *
 * <p>伪造一次假查询能把冲突变成确定性的, 看着很划算, 但第一版这么写跑不通, 也不该跑通:
 * Spring Data 的仓储是接口代理, 方法在接口上就是抽象的, Mockito 没法「调用真实方法」,
 * 只能再手工把原 bean 传回去, 越绕越假.
 *
 * <p>更重要的是分工: 真库这边该验的是「约束真的存在、真的咬人、正常重复提交只有一行」,
 * 这些**不需要**伪造就成立. 至于「插入撞了约束之后服务层怎么收场」——那是控制流,
 * 拿 mock 来验才准, 放在 {@code TrackServiceTest} / {@code ReviewServiceTest} /
 * {@code StatsServiceTest} 里.
 *
 * <p><b>类上刻意不加 @Transactional</b>
 *
 * <p>加了这个, 每个测试方法自己就成了一个外层事务, 用例之间互相看得见对方没提交的行,
 * 计数断言全靠运气. 而且生产上的网页请求本来就没有外层事务, 每条 repository 调用
 * 各自提交 —— 不挂事务才更接近真实路径.
 */
@SpringBootTest(properties = {
        "spring.datasource.url=jdbc:h2:mem:anitrack-write-conflict;DB_CLOSE_DELAY=-1;MODE=MySQL"
})
@ActiveProfiles("dev")
class WriteConflictIntegrationTest {

    @Autowired
    private TrackService trackService;
    @Autowired
    private ReviewService reviewService;
    @Autowired
    private StatsService statsService;
    @Autowired
    private UserRepository userRepository;
    @Autowired
    private JdbcTemplate jdbc;

    /**
     * 每个用例开始前清空这三张表.
     *
     * 用例之间共享同一个 Spring 上下文和同一个内存库, 而这里断言的是「表里有几行」——
     * 上一条用例留下的行会让下一条误判, 而且失败方式还很隐蔽: 单独跑绿, 全量跑红.
     */
    @BeforeEach
    void clearBusinessTables() {
        jdbc.execute("DELETE FROM episode_watched");
        jdbc.execute("DELETE FROM review");
        jdbc.execute("DELETE FROM anime_tracking");
    }

    private User freshUser() {
        return userRepository.save(User.builder()
                .username("u" + UUID.randomUUID().toString().substring(0, 8))
                .password("x")
                .role("USER")
                .status("ACTIVE")
                .build());
    }

    private static TrackRequest trackReq(int subjectId, String status, int progress) {
        TrackRequest req = new TrackRequest();
        req.setSubjectId(subjectId);
        req.setStatus(status);
        req.setProgress(progress);
        return req;
    }

    private static ReviewRequest reviewReq(int subjectId, int rating, String content) {
        ReviewRequest req = new ReviewRequest();
        req.setSubjectId(subjectId);
        req.setRating(rating);
        req.setContent(content);
        return req;
    }

    /** 直接数行, 不经过被测代码那条路 */
    private long rows(String table) {
        Long n = jdbc.queryForObject("SELECT COUNT(*) FROM " + table, Long.class);
        return n == null ? 0 : n;
    }

    // ========== 约束真的在、真的咬人 ==========

    /**
     * 用裸 SQL 撞一次约束.
     *
     * 绕开 JPA 是故意的: 走 JPA 的话, 这个失败有可能来自实体注解建的约束(create-drop 的
     * 测试库), 也有可能来自迁移脚本 —— 分不清是哪一个在起作用. 而应用实际跑的库是
     * Flyway 建的, 所以这里要证明的正是**迁移脚本建出来的那个约束**在挡人.
     * 本用例的库是 ddl-auto=validate + Flyway, 不存在 Hibernate 建表的可能.
     */
    @Test
    @DisplayName("三张表上的唯一约束都真实存在: 裸 SQL 插重复行会被数据库挡回来")
    void uniqueConstraintsRejectDuplicateRowsAtTheDatabaseLevel() {
        User user = freshUser();
        Long uid = user.getId();

        jdbc.update("INSERT INTO anime_tracking (user_id, subject_id, status, progress) "
                + "VALUES (?, 100, 'watching', 1)", uid);
        assertThatThrownBy(() -> jdbc.update(
                "INSERT INTO anime_tracking (user_id, subject_id, status, progress) "
                        + "VALUES (?, 100, 'watched', 2)", uid))
                .isInstanceOf(DataIntegrityViolationException.class);

        jdbc.update("INSERT INTO review (user_id, subject_id, rating, content) "
                + "VALUES (?, 200, 8, '还行')", uid);
        assertThatThrownBy(() -> jdbc.update(
                "INSERT INTO review (user_id, subject_id, rating, content) "
                        + "VALUES (?, 200, 9, '又一条')", uid))
                .isInstanceOf(DataIntegrityViolationException.class);

        jdbc.update("INSERT INTO episode_watched (user_id, anime_id, episode_num) "
                + "VALUES (?, 300, 1)", uid);
        assertThatThrownBy(() -> jdbc.update(
                "INSERT INTO episode_watched (user_id, anime_id, episode_num) "
                        + "VALUES (?, 300, 1)", uid))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    @DisplayName("约束没写宽: 换个 subject_id, 同一用户照样能建第二条")
    void constraintsDoNotBlockLegitimateSecondRows() {
        User user = freshUser();

        trackService.saveTracking(user, trackReq(100, "watching", 1));
        trackService.saveTracking(user, trackReq(101, "watching", 1));

        assertThat(rows("anime_tracking")).isEqualTo(2);
    }

    // ========== 重复提交只有一行 ==========

    @Test
    @DisplayName("同一个人对同一部番连发两次追番: 库里只有一行, 第二次是改不是加")
    void repeatedTrackingSavesLeaveExactlyOneRow() {
        User user = freshUser();

        trackService.saveTracking(user, trackReq(100, "want_to_watch", 0));
        trackService.saveTracking(user, trackReq(100, "watching", 3));

        assertThat(rows("anime_tracking")).isEqualTo(1);
        assertThat(jdbc.queryForObject(
                "SELECT status FROM anime_tracking WHERE subject_id = 100", String.class))
                .isEqualTo("watching");
        assertThat(jdbc.queryForObject(
                "SELECT progress FROM anime_tracking WHERE subject_id = 100", Integer.class))
                .isEqualTo(3);
    }

    @Test
    @DisplayName("同一部番连发两次短评: 库里只有一行, 第二次覆盖第一次")
    void repeatedReviewSavesLeaveExactlyOneRow() {
        User user = freshUser();

        reviewService.saveReview(user, reviewReq(200, 6, "第一版"));
        reviewService.saveReview(user, reviewReq(200, 9, "改过之后"));

        assertThat(rows("review")).isEqualTo(1);
        assertThat(jdbc.queryForObject(
                "SELECT rating FROM review WHERE subject_id = 200", Integer.class)).isEqualTo(9);
        assertThat(jdbc.queryForObject(
                "SELECT content FROM review WHERE subject_id = 200", String.class))
                .isEqualTo("改过之后");
    }

    @Test
    @DisplayName("同一集连点两次打勾: 回到未看, 库里不留行")
    void togglingTheSameEpisodeTwiceEndsUpUnwatched() {
        User user = freshUser();

        assertThat(statsService.toggleEpisode(user, 300, 1)).isTrue();
        assertThat(rows("episode_watched")).isEqualTo(1);

        // 这里测的是删除路径 —— 事务边界从 StatsService 挪到了仓储方法上,
        // 挪漏了的话这一步会直接报「没有事务」, 而不是安静地失败
        assertThat(statsService.toggleEpisode(user, 300, 1)).isFalse();
        assertThat(rows("episode_watched")).isZero();
    }

    // ========== 打勾顺带同步追番进度 ==========

    /**
     * c98 的**主要守卫**.
     *
     * <p>「打勾会顺手建一条在看记录」这件事, 单元用例只能验到「调用了哪个方法传了什么
     * 参数」; 真正要钉的是**库里最后剩下什么**——尤其是「只往前推」这条: 取消打勾之后
     * 进度**不许**退回去. 实现里把取消分支也接上 sync 是最容易犯的错, 而它在网页上
     * 看起来完全正常(勾没了, 进度也跟着退一格, 像是"一致"的).
     */
    @Test
    @DisplayName("打勾建一条「在看」并把进度推到这一集; 取消打勾只删勾, 记录和进度都留着")
    void togglingAnEpisodeSyncsTheTrackingProgress() {
        User user = freshUser();

        assertThat(statsService.toggleEpisode(user, 300, 1)).isTrue();
        assertThat(rows("anime_tracking")).isEqualTo(1);
        assertThat(jdbc.queryForObject(
                "SELECT status FROM anime_tracking WHERE subject_id = 300", String.class))
                .isEqualTo("watching");
        assertThat(jdbc.queryForObject(
                "SELECT progress FROM anime_tracking WHERE subject_id = 300", Integer.class))
                .isEqualTo(1);

        assertThat(statsService.toggleEpisode(user, 300, 1)).isFalse();
        assertThat(rows("episode_watched")).isZero();
        assertThat(rows("anime_tracking")).as("取消打勾不该把追番记录一起删掉").isEqualTo(1);
        assertThat(jdbc.queryForObject(
                "SELECT progress FROM anime_tracking WHERE subject_id = 300", Integer.class))
                .as("进度是水位线, 取消打勾不该让它退回去").isEqualTo(1);
    }

    @Test
    @DisplayName("进度是水位线: 打到第 5 集之后回头点第 3 集, 仍然停在 5")
    void progressNeverMovesBackwards() {
        User user = freshUser();

        statsService.toggleEpisode(user, 301, 5);
        statsService.toggleEpisode(user, 301, 3);

        assertThat(jdbc.queryForObject(
                "SELECT progress FROM anime_tracking WHERE subject_id = 301", Integer.class))
                .isEqualTo(5);
        assertThat(rows("episode_watched")).as("两个勾都算数").isEqualTo(2);
    }

    @Test
    @DisplayName("已经有追番记录时只推进度, 不动用户自己设过的状态")
    void togglingKeepsTheStatusTheUserAlreadySet() {
        User user = freshUser();
        trackService.saveTracking(user, trackReq(302, "on_hold", 0));

        statsService.toggleEpisode(user, 302, 2);

        assertThat(rows("anime_tracking")).isEqualTo(1);
        assertThat(jdbc.queryForObject(
                "SELECT status FROM anime_tracking WHERE subject_id = 302", String.class))
                .isEqualTo("on_hold");
        assertThat(jdbc.queryForObject(
                "SELECT progress FROM anime_tracking WHERE subject_id = 302", Integer.class))
                .isEqualTo(2);
    }

    // ========== 登录失败计数: 自增发生在数据库里 ==========

    /**
     * 并发自增不丢计数.
     *
     * <p>这条钉的是「+1 由数据库那条 UPDATE 完成」. 原来的写法是读出来 +1 再 save,
     * 典型读-改-写: 所有线程读到同一个值, 各自写回同一个值, 计出来的数小于真实
     * 失败次数. 而阈值判定读的正是这个数 —— 计数涨得比尝试次数慢, 爆破窗口就被
     * 悄悄拉长, 日志上一切正常.
     *
     * <p>用闩锁把 8 个线程卡在同一起跑线上, 是为了把竞态窗口撞开. 说清楚这个用例
     * 的能力边界: 老写法下它**不是每次都红**(竞态是概率的), 但一旦真丢一次就是
     * 确定性失败; 新写法下它稳定通过. 这里的 8 次并发各自只是一条 autocommit 的
     * UPDATE, 行锁持有时间是微秒级, 撞不到 H2 的锁超时, 所以它不会变成一条
     * 时好时坏的用例.
     */
    @Test
    @DisplayName("并发自增失败计数不会丢: 8 次并发自增之后正好是 8")
    void concurrentFailureCountingLosesNothing() throws Exception {
        Long uid = freshUser().getId();
        int threads = 8;
        CountDownLatch start = new CountDownLatch(1);
        CountDownLatch done = new CountDownLatch(threads);
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        try {
            for (int i = 0; i < threads; i++) {
                pool.execute(() -> {
                    try {
                        start.await();
                        userRepository.incrementFailedAttempts(uid);
                    } catch (InterruptedException ie) {
                        Thread.currentThread().interrupt();
                    } finally {
                        done.countDown();
                    }
                });
            }
            start.countDown();
            assertThat(done.await(30, TimeUnit.SECONDS)).isTrue();
        } finally {
            pool.shutdownNow();
        }

        // 表名带引号: 它是保留字, 迁移脚本建的是小写的 "user"(见 V1__init_schema.sql)
        assertThat(jdbc.queryForObject(
                "SELECT failed_attempts FROM \"user\" WHERE id = ?", Integer.class, uid))
                .isEqualTo(threads);
    }

    /**
     * 老账号的 failed_attempts 是 NULL —— 加这一列时没有 DEFAULT(见 User 实体上的说明).
     * SQL 里 NULL + 1 仍然是 NULL, 少了 COALESCE, 这些账号的计数会永远停在空值上,
     * 阈值等于不存在, 而每一种写法看起来都很正常.
     */
    @Test
    @DisplayName("计数为 NULL 的老账号自增得到 1, 而不是继续为空")
    void incrementTreatsNullAsZero() {
        Long uid = freshUser().getId();
        jdbc.update("UPDATE \"user\" SET failed_attempts = NULL WHERE id = ?", uid);

        userRepository.incrementFailedAttempts(uid);

        assertThat(jdbc.queryForObject(
                "SELECT failed_attempts FROM \"user\" WHERE id = ?", Integer.class, uid))
                .isEqualTo(1);
    }

    /**
     * 清零和锁定必须在同一条 UPDATE 里.
     *
     * 拆成两条的危险不是「少清一次」: 计数留在阈值上, 锁定期一满、用户再错一次就
     * 立刻又被锁上 —— 限时锁定退化成永久锁定, 而且锁定接口看起来一切正常.
     * 从 readFailedAttempts 之外的地方读回实体, 顺便验了时间戳真的落库成了未来时刻.
     */
    @Test
    @DisplayName("锁定时清零: 锁定后计数为 0 且确实处于锁定期")
    void lockAndResetClearsCounterInTheSameStatement() {
        Long uid = freshUser().getId();
        jdbc.update("UPDATE \"user\" SET failed_attempts = 5 WHERE id = ?", uid);
        LocalDateTime until = LocalDateTime.now().plusMinutes(15);

        userRepository.lockAndResetFailures(uid, until);

        User reloaded = userRepository.findById(uid).orElseThrow();
        assertThat(reloaded.failedAttemptsOrZero()).isZero();
        assertThat(reloaded.isLocked()).isTrue();
    }
}
