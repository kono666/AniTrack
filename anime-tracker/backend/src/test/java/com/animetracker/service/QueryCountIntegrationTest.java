package com.animetracker.service;

import com.animetracker.entity.User;
import com.animetracker.repository.UserRepository;
import jakarta.persistence.EntityManagerFactory;
import org.hibernate.SessionFactory;
import org.hibernate.stat.Statistics;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 「这个接口发了几条 SQL」——按真实执行计数, 而不是看代码里写了几个 for 循环.
 *
 * <p><b>为什么必须有这一层</b>
 *
 * <p>N+1 的坏处全在次数上, 结果一个字节都不差: 逐条查和批量查返回的列表完全一样.
 * 所以任何断言返回值的用例(单元测试里那些, 以及网页上点一遍)都不可能发现它 ——
 * 这也是它能一路活到现在的原因. 只有真的去数 SQL 条数, 才谈得上"修好了".
 *
 * <p>计数用 Hibernate 自己的 Statistics, 不引额外的依赖(datasource-proxy 之类):
 * 打开 {@code hibernate.generate_statistics} 之后, {@code getPrepareStatementCount()}
 * 就是这一个 SessionFactory 真正 prepare 过的语句数.
 *
 * <p><b>为什么断言的是"恒定"而不是某个具体数字</b>
 *
 * <p>真正要防的回归是「有人日后又在循环里补一次查询」. 那种改动不会让次数从 2 变成 3,
 * 而是变成 2+N —— 所以把「1 条、5 条、50 条追番都是 2 次查询」摆在一起断言,
 * 才说明次数与数据量无关. 只测一个数据量的话, 恰好等于某个数字也能过.
 *
 * <p>这里刻意不用 @Transactional: 与 {@link WriteConflictIntegrationTest} 同一个理由,
 * 网页请求本来就没有外层事务, 而外层事务还会让统计口径变得不是"这次动作发了什么".
 */
@SpringBootTest(properties = {
        "spring.datasource.url=jdbc:h2:mem:anitrack-query-count;DB_CLOSE_DELAY=-1;MODE=MySQL",
        "spring.jpa.properties.hibernate.generate_statistics=true"
})
@ActiveProfiles("dev")
class QueryCountIntegrationTest {

    /** 与启动预加载灌进来的那批番剧 id 错开, 免得被它们的行数干扰 */
    private static final int SUBJECT_BASE = 96000000;

    @Autowired
    private EntityManagerFactory entityManagerFactory;
    @Autowired
    private JdbcTemplate jdbc;
    @Autowired
    private TrackService trackService;
    @Autowired
    private StatsService statsService;
    @Autowired
    private ReviewService reviewService;
    @Autowired
    private AdminService adminService;
    @Autowired
    private UserRepository userRepository;

    private User user;

    @BeforeEach
    void seedUser() {
        jdbc.execute("DELETE FROM anime_tracking");
        jdbc.execute("DELETE FROM anime WHERE id >= " + SUBJECT_BASE);
        jdbc.execute("DELETE FROM review");
        user = userRepository.save(User.builder()
                .username("q" + UUID.randomUUID().toString().substring(0, 8))
                .password("x")
                .role("USER")
                .status("ACTIVE")
                .build());
    }

    /** 灌 n 条追番, 每条都配一部本地已缓存的番剧 */
    private void seedTrackings(int n) {
        for (int i = 0; i < n; i++) {
            int subjectId = SUBJECT_BASE + i;
            jdbc.update("INSERT INTO anime (id, title, tags) VALUES (?, ?, ?)",
                    subjectId, "番" + i, "科幻");
            jdbc.update("INSERT INTO anime_tracking (user_id, subject_id, status, progress) "
                    + "VALUES (?, ?, 'watching', 0)", user.getId(), subjectId);
        }
    }

    /** 灌 n 条短评, 每条来自一个不同的用户 —— 作者各不相同的列表才是 N+1 的重灾区 */
    private void seedReviews(int n) {
        // created_at 显式给成递增的时刻: 列表是按它倒序的, 全 NULL 的话"第几页是哪几条"
        // 就成了运气, 而下面要断言的正是切页切对了没有
        long base = java.sql.Timestamp.valueOf("2030-01-01 00:00:00").getTime();
        for (int i = 0; i < n; i++) {
            User author = userRepository.save(User.builder()
                    .username("r" + UUID.randomUUID().toString().substring(0, 12))
                    .password("x")
                    .role("USER")
                    .status("ACTIVE")
                    .build());
            jdbc.update("INSERT INTO review (user_id, subject_id, rating, content, created_at) "
                            + "VALUES (?, ?, 8, ?, ?)",
                    author.getId(), SUBJECT_BASE, "c" + i,
                    new java.sql.Timestamp(base + i * 60_000L));
        }
    }

    /** 只数这次动作真正发出去的语句. 灌数据的那几条在 clear() 之前, 不计入. */
    private long statementsFor(Runnable action) {
        Statistics stats = entityManagerFactory.unwrap(SessionFactory.class).getStatistics();
        stats.clear();
        action.run();
        return stats.getPrepareStatementCount();
    }

    // ========== 追番列表 ==========

    /**
     * 改动前这里是 1+N: 一条查追番, 然后每条追番一次 findById.
     * 数据量越大越慢, 而返回值一模一样 —— 所以这个数字是唯一能盯着它的东西.
     */
    @ParameterizedTest(name = "{0} 条追番")
    @ValueSource(ints = {1, 5, 50})
    @DisplayName("追番列表: 不论多少条都是 2 次查询(一条查追番, 一条批量查番剧)")
    void trackingListCostsTwoQueriesRegardlessOfRowCount(int n) {
        seedTrackings(n);

        long statements = statementsFor(() -> trackService.getUserTrackings(user));

        assertThat(statements).as("追番 %d 条", n).isEqualTo(2);
    }

    @Test
    @DisplayName("追番列表: 结果是按 updated_at 倒序的完整列表, 没有因为批量取而缺行或错序")
    void trackingListStillReturnsEveryRowInOrder() {
        seedTrackings(3);
        // 刻意把三行的时间设成与插入顺序**相反**: 批量取番剧时按 id 装进 Map 再取出来,
        // 一旦谁不小心拿 Map 的遍历顺序当顺序, 这里就会露出来. 三行都给明确的非空时间,
        // 免得把 H2 对 NULL 的排序规则也一起断言进去
        stamp(SUBJECT_BASE, "2030-01-01 00:00:00");
        stamp(SUBJECT_BASE + 1, "2025-06-01 00:00:00");
        stamp(SUBJECT_BASE + 2, "2020-01-01 00:00:00");

        List<Map<String, Object>> list = trackService.getUserTrackings(user);

        assertThat(list).hasSize(3);
        assertThat(list).extracting(m -> m.get("subjectId"))
                .containsExactly(SUBJECT_BASE, SUBJECT_BASE + 1, SUBJECT_BASE + 2);
        assertThat(list.get(0)).containsEntry("animeTitle", "番0");
        assertThat(list.get(2)).containsEntry("animeTitle", "番2");
    }

    /** 把某条追番的 updated_at 钉成指定时刻 */
    private void stamp(int subjectId, String timestamp) {
        jdbc.update("UPDATE anime_tracking SET updated_at = ? WHERE subject_id = ?",
                java.sql.Timestamp.valueOf(timestamp), subjectId);
    }

    /**
     * 一条追番都没有时只该有 1 次查询 —— 那条批量查询根本不该发出去.
     *
     * <p>这条同时钉住代码里那句注释的口头承诺("findAllById 收到空集合会直接返回空列表,
     * 不会拼出 `WHERE id IN ()`"): 空 IN 列表在 H2 上直接是语法错误, 而首页在用户
     * 还没追任何番的时候一定会走到这条路.
     */
    @Test
    @DisplayName("没有追番时只查一次: 空集合的批量查询不会真的发出去")
    void emptyTrackingListCostsASingleQuery() {
        assertThat(statementsFor(() -> trackService.getUserTrackings(user))).isEqualTo(1);
    }

    // ========== 类型分布 / 最近活动 ==========

    @Test
    @DisplayName("类型分布: 一次批量取番剧, 总共 2 次查询")
    void genreDistributionCostsTwoQueries() {
        seedTrackings(5);

        Map<String, Integer> genre = statsService.getGenreDistribution(user);

        assertThat(genre).containsEntry("科幻", 5);
        assertThat(statementsFor(() -> statsService.getGenreDistribution(user))).isEqualTo(2);
    }

    /**
     * 12 条追番而只展示 10 条.
     *
     * <p>除了次数是 2, 这里还要看**取回来的是不是只有 10 条** —— 改动前是先把 12 条
     * 全读进内存再丢掉 2 条, 那种写法查询次数同样是 2, 只有行数能区分它们.
     */
    @Test
    @DisplayName("最近活动: 只取 10 条 + 一次批量取番剧, 2 次查询")
    void recentActivityCostsTwoQueriesAndOnlyTakesTenRows() {
        seedTrackings(12);

        List<Map<String, Object>> activity = statsService.getRecentActivity(user);

        assertThat(activity).hasSize(10);
        assertThat(statementsFor(() -> statsService.getRecentActivity(user))).isEqualTo(2);
    }

    // ========== 评论列表 ==========

    /**
     * 改动前是 1+N: 一条查评论, 然后每条评论一次「按 id 查作者」.
     * 作者各不相同的时候最明显 —— 每条都要单独查一次.
     */
    @ParameterizedTest(name = "{0} 条评论")
    @ValueSource(ints = {1, 5, 20})
    @DisplayName("评论列表: 不论多少条都是 1 次查询(JOIN FETCH 把作者一起带回来)")
    void reviewListCostsASingleQueryRegardlessOfRowCount(int n) {
        seedReviews(n);

        long statements = statementsFor(() -> reviewService.getSubjectReviews(0L, SUBJECT_BASE, 1, 20));

        assertThat(statements).as("评论 %d 条", n).isEqualTo(1);
    }

    /**
     * 分页必须真的切在数据库上, 而不是「全读出来再在内存里切」.
     *
     * <p>查询次数分辨不出这两种写法(都是 1 次), 只有"拿回来的是哪几条"能.
     * 顺带也钉住 ORDER BY 确实生效: 没有它, 第 2 页可能和上半页是同一批数据.
     */
    @Test
    @DisplayName("评论列表: 翻页拿到的确实是下一批, 按时间倒序")
    void reviewListPagingSlicesInTheDatabase() {
        seedReviews(10);

        List<String> firstPage = contentsOf(reviewService.getSubjectReviews(0L, SUBJECT_BASE, 1, 5));
        List<String> secondPage = contentsOf(reviewService.getSubjectReviews(0L, SUBJECT_BASE, 2, 5));

        // c9 是最晚写的, 倒序排第一
        assertThat(firstPage).containsExactly("c9", "c8", "c7", "c6", "c5");
        assertThat(secondPage).containsExactly("c4", "c3", "c2", "c1", "c0");
    }

    private static List<String> contentsOf(List<Map<String, Object>> reviews) {
        return reviews.stream().map(m -> (String) m.get("content")).toList();
    }

    /**
     * 管理端列表同样是 JOIN FETCH.
     *
     * <p>这条还顺带证明 {@code Pageable.unpaged()} 真的当"不分页"在用 —— 管理端的
     * 分页留到 4.6, 现在必须把全部评论取回来, 一个不少.
     */
    @Test
    @DisplayName("管理端评论列表: 1 次查询取回全部评论(含作者)")
    void adminReviewListCostsASingleQuery() {
        seedReviews(6);

        List<Map<String, Object>> all = adminService.getAllReviews();

        assertThat(all).hasSize(6);
        assertThat(all.get(0)).containsKeys("username", "userId", "subjectId");
        assertThat(statementsFor(() -> adminService.getAllReviews())).isEqualTo(1);
    }
}
