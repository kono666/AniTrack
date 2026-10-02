package com.animetracker.service;

import com.animetracker.entity.AnimeTracking;
import com.animetracker.entity.LoginEvent;
import com.animetracker.entity.Review;
import com.animetracker.entity.User;
import com.animetracker.repository.LoginEventRepository;
import com.animetracker.repository.ReviewRepository;
import com.animetracker.repository.TrackingRepository;
import com.animetracker.repository.UserRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;

import java.time.LocalDate;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 看板的时间维度在真库上算出来的都是什么 —— 窗口边界、软删口径、以及老键一个没少。
 *
 * <p>它管的是三件 mock 看不见的事:
 * <ul>
 *   <li><b>窗口边界。</b>「近 7 天」是含今天的 7 个自然日(今天零点往前推 6 天), 不是滚动
 *       7×24 小时, 也不是 7 天前那一刻。差一天这种事在代码里看不出来, 只有拿真行去试
 *       才知道; 而看板上「近 7 天新增用户」与「周活」并排显示, 两处用了不同的窗口时
 *       没人解释得清。</li>
 *   <li><b>与累计值同一个口径。</b>「近 N 天新增评论」必须和「总评论数」一样不数已移除的
 *       (V14 的软删), 否则会出现「新增比总数涨得还快」这种没法解释的组合。</li>
 *   <li><b>六个老键还在。</b> Agent 的 {@code platform_dashboard} 工具与前端看板都在读它们,
 *       这次只该往里加, 不该动旧的。</li>
 * </ul>
 *
 * <p>库名单独起一个, 并且 {@code @BeforeEach} 会把引导账号(DataInitializer 建的)一起清掉:
 * 「近 7 天新增用户」是个绝对数, 库里若有两个刚刚被创建出来的铺底账号, 每一条窗口断言
 * 都会平白多出 2 来。
 */
@SpringBootTest(properties = {
        "spring.datasource.url=jdbc:h2:mem:anitrack-dashboard;DB_CLOSE_DELAY=-1;MODE=MySQL",
        "anitrack.preload.enabled=false"
})
@ActiveProfiles("dev")
class AdminDashboardIntegrationTest {

    @Autowired
    private AdminService adminService;
    @Autowired
    private LoginEventService loginEventService;
    @Autowired
    private UserRepository userRepository;
    @Autowired
    private ReviewRepository reviewRepository;
    @Autowired
    private TrackingRepository trackingRepository;
    @Autowired
    private LoginEventRepository loginEventRepository;
    @Autowired
    private JdbcTemplate jdbc;

    /** 先删指着 user 的那些, 再删 user —— 外键都在前面这几张表上 */
    /** {@link #nextSubject()} 的计数器 */
    private int subjectSeed = 0;

    @BeforeEach
    void clear() {
        subjectSeed = 0;
        jdbc.execute("DELETE FROM notification");
        jdbc.execute("DELETE FROM user_avatar");
        jdbc.execute("DELETE FROM review");
        jdbc.execute("DELETE FROM anime_tracking");
        jdbc.execute("DELETE FROM login_event");
        jdbc.execute("DELETE FROM \"user\"");
    }

    // ========== 六个老键 ==========

    /**
     * 这次只该往看板里**加**东西。
     *
     * <p>六个键的名字被两处读着: Agent 的 {@code platform_dashboard} 工具(模型据此写结论)
     * 与前端 {@code admin/Dashboard.vue}。改名的后果不是报错, 是那两处安静地读到
     * {@code undefined} —— 前端显示成空白卡片, 模型则开始编数字。
     */
    @Test
    @DisplayName("六个老键一个没少, 名字与口径都没动")
    void theLegacyCountersAreUntouched() {
        User u = user("dash_legacy", LocalDate.now().minusDays(100));
        tracking(u, LocalDate.now().minusDays(100));
        review(u, LocalDate.now().minusDays(100), false);
        review(u, LocalDate.now().minusDays(100), true);   // 已移除的那一条

        Map<String, Object> data = adminService.getDashboard();

        assertThat(data).containsKeys("totalUsers", "adminUsers", "activeUsers", "disabledUsers",
                "totalReviews", "totalTrackings");
        assertThat(data.get("totalUsers")).isEqualTo(1L);
        assertThat(data.get("totalReviews"))
                .as("总评论数一向不数已移除的(V14 软删), 新增那两项必须跟它同一个口径")
                .isEqualTo(1L);
        assertThat(data.get("totalTrackings")).isEqualTo(1L);
    }

    // ========== 时间维度: 窗口边界 ==========

    /**
     * 「近 N 天」= 含今天的 N 个自然日, 起点是今天零点往前推 N−1 天。
     *
     * <p>几条日期是照着边界挑的, 每一个都钉住一侧:
     * <ul>
     *   <li>{@code today-6} 恰好在 7 天窗内(第 7 天, 最老的那一天);</li>
     *   <li>{@code today-7} 恰好出窗 —— 差一天的实现会把它算进去, 7 天窗就变成 8 天;</li>
     *   <li>{@code today-29} 恰好在 30 天窗内; {@code today-30} 恰好出窗。</li>
     * </ul>
     */
    @Test
    @DisplayName("近 7 天 / 近 30 天新增用户: 含今天, 边界正好卡在第 7 天与第 30 天")
    void userGrowthWindowsLandOnTheRightBoundaries() {
        LocalDate today = LocalDate.now();
        user("dash_d0", today);
        user("dash_d6", today.minusDays(6));     // 7 天窗里最老的那一天
        user("dash_d7", today.minusDays(7));     // 刚好出 7 天窗
        user("dash_d29", today.minusDays(29));   // 30 天窗里最老的那一天
        user("dash_d30", today.minusDays(30));   // 两个窗都在外面

        Map<String, Object> growth = growthOf(adminService.getDashboard());

        assertThat(counts(growth, "last7d").get("users"))
                .as("今天 + 6 天前 = 2; 7 天前那天不算 —— 算了就变成「近 8 天」")
                .isEqualTo(2L);
        assertThat(counts(growth, "last30d").get("users"))
                .as("今天 / 6 / 7 / 29 天前 = 4; 30 天前那天不算")
                .isEqualTo(4L);
    }

    @Test
    @DisplayName("近 7/30 天新增评论与追番, 且「新增评论」不数已移除的")
    void reviewAndTrackingGrowthFollowTheSameWindows() {
        LocalDate today = LocalDate.now();
        User u = user("dash_content", today.minusDays(100));

        review(u, today.minusDays(3), false);
        review(u, today.minusDays(40), false);
        review(u, today.minusDays(3), true);      // 同一天发的, 但已被移除
        tracking(u, today.minusDays(3));
        tracking(u, today.minusDays(40));

        Map<String, Object> growth = growthOf(adminService.getDashboard());

        assertThat(counts(growth, "last7d").get("reviews"))
                .as("两条是 3 天前发的, 但其中一条已被移除 —— 新增与总数必须同一个口径")
                .isEqualTo(1L);
        assertThat(counts(growth, "last30d").get("reviews")).isEqualTo(1L);
        assertThat(counts(growth, "last7d").get("trackings")).isEqualTo(1L);
        assertThat(counts(growth, "last30d").get("trackings")).isEqualTo(1L);
    }

    // ========== 活跃度那一块接上了 ==========

    @Test
    @DisplayName("看板里的 activity 就是 LoginEventService 那一份, 而且只数成功的那一半")
    void theActivityBlockIsWiredIn() {
        loginEventRepository.save(LoginEvent.builder()
                .userId(1L).createdAt(LocalDate.now().atTime(12, 0)).success(true).build());
        loginEventRepository.save(LoginEvent.builder()
                .userId(2L).createdAt(LocalDate.now().atTime(12, 30)).success(false).build());

        Map<String, Object> activity = activityOf(adminService.getDashboard());

        assertThat(activity.get("dau"))
                .as("失败那一条不是一个人来过, 它只是有人试过")
                .isEqualTo(1L);
        assertThat(activity.get("trackedSince")).isEqualTo(LocalDate.now().toString());
        assertThat(activity.get("trend")).asList().hasSize(14);
    }

    // ========== 工具 ==========

    /**
     * 每建一条短评/追番换一个 Bangumi subject_id。
     *
     * <p>两张表上都有「同一用户对同一部番只有一条」的唯一约束(V3), 而这里的用例要在
     * 同一个人名下造好几条 —— 复用同一个 subject_id 的话第二条会被约束拦下来,
     * 失败信息还长得像"窗口算错了"。
     */
    private int nextSubject() {
        return ++subjectSeed;
    }

    /** 建一个用户, 再把 {@code created_at} 改成指定那天 —— 实体里的 {@code @PrePersist} 只会写"现在" */
    private User user(String username, LocalDate createdOn) {
        User u = userRepository.save(User.builder()
                .username(username)
                .password("x")
                .email(username + "@example.com")
                .role("USER")
                .status("ACTIVE")
                .failedAttempts(0)
                .build());
        // 表名要带引号: user 是保留字, 而这张表就是带引号建的小写 "user"
        backdate("\"user\"", u.getId(), createdOn);
        return u;
    }

    private void review(User u, LocalDate createdOn, boolean removed) {
        Review r = reviewRepository.save(Review.builder()
                .user(u).subjectId(nextSubject()).rating(8).content("dash-test").build());
        backdate("review", r.getId(), createdOn);
        if (removed) {
            jdbc.update("UPDATE review SET deleted_at = ? WHERE id = ?",
                    createdOn.atTime(12, 0), r.getId());
        }
    }

    private void tracking(User u, LocalDate createdOn) {
        AnimeTracking t = trackingRepository.save(AnimeTracking.builder()
                .user(u).subjectId(nextSubject()).status("watching").build());
        backdate("anime_tracking", t.getId(), createdOn);
    }

    /**
     * 把 {@code created_at} 改成过去某天。
     *
     * <p>走 JDBC 而不是实体, 是因为两张表的 {@code @PrePersist} 都会无条件覆盖这一列
     * ("插入时刻"本来就是它该有的值), 而这里要的恰恰是伪造一段历史 ——
     * 没有这个手段, 窗口边界就只能对着一个"所有行都是今天"的库断言, 什么也验不出来。
     */
    private void backdate(String table, Long id, LocalDate day) {
        jdbc.update("UPDATE " + table + " SET created_at = ? WHERE id = ?",
                day.atTime(12, 0), id);
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> growthOf(Map<String, Object> dashboard) {
        return (Map<String, Object>) dashboard.get("growth");
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> activityOf(Map<String, Object> dashboard) {
        return (Map<String, Object>) dashboard.get("activity");
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> counts(Map<String, Object> growth, String window) {
        return (Map<String, Object>) growth.get(window);
    }
}
