package com.animetracker.service;

import com.animetracker.entity.Anime;
import com.animetracker.entity.User;
import com.animetracker.repository.AnimeRepository;
import com.animetracker.repository.AnimeTagRepository;
import com.animetracker.repository.UserRepository;
import com.animetracker.util.SearchPatterns;
import com.animetracker.util.TagTranslationUtil;
import jakarta.persistence.EntityManagerFactory;
import org.hibernate.SessionFactory;
import org.hibernate.resource.jdbc.spi.StatementInspector;
import org.hibernate.stat.Statistics;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.cache.CacheManager;
import org.springframework.data.domain.PageRequest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;

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
        "spring.jpa.properties.hibernate.generate_statistics=true",
        // 关掉启动预加载: 它在后台线程里往 anime / anime_tag 插数据, 而那些插入的语句
        // 和用例自己发的算在同一个 SessionFactory 的统计里, 会让下面的条数与行数
        // 变成"看后台跑到哪了"的随机值(实测: 同一个用例曾经数出 1 条, 也数出 5 条).
        // 这个类要断言的是"这次动作读了几行", 所以库必须是安静的
        "anitrack.preload.enabled=false",
        // 记下执行的 SQL 文本, 供"生成的语句长什么样"这类断言使用(见 SqlRecorder)
        "spring.jpa.properties.hibernate.session_factory.statement_inspector="
                + "com.animetracker.service.QueryCountIntegrationTest$SqlRecorder"
})
@ActiveProfiles("dev")
class QueryCountIntegrationTest {

    /**
     * 把最近一条执行的 SQL 留下来.
     *
     * <p>为什么还要这一层: 语句条数与读入行数都看不见"这条 SQL 有没有行数限制" ——
     * 读一行和读一万行都是一条语句、零个实体. 而有些改动(比如把 COUNT 整表换成
     * exists)的全部意义就在于生成的语句里多了个限制.
     *
     * <p>它是 Hibernate 自己的扩展点, 由上面 properties 里的
     * {@code hibernate.session_factory.statement_inspector} 指过来.
     * 静态字段是刻意的: Hibernate 自己 new 这个类, 测试拿不到那个实例.
     */
    public static class SqlRecorder implements StatementInspector {
        private static final AtomicReference<String> LAST = new AtomicReference<>();

        @Override
        public String inspect(String sql) {
            LAST.set(sql);
            return sql;
        }

        static String last() {
            return LAST.get();
        }
    }

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
    private AnimeService animeService;
    @Autowired
    private AnimeRepository animeRepository;
    @Autowired
    private AnimeTagRepository animeTagRepository;
    @Autowired
    private UserRepository userRepository;
    /** 排行榜那条路带 @Cacheable, 而"读了几行"的断言不能被上一次调用的缓存命中搅乱 */
    @Autowired
    private CacheManager cacheManager;

    private User user;

    @BeforeEach
    void seedUser() {
        jdbc.execute("DELETE FROM anime_tracking");
        jdbc.execute("DELETE FROM anime WHERE id >= " + SUBJECT_BASE);
        jdbc.execute("DELETE FROM review");
        // 标签: 先删关联行再删标签行(fk_anime_tag_tag 挡着). 两个条件各管一类 ——
        // 挂在自建番剧上的, 和为了撑行数灌进去的噪声行
        jdbc.execute("DELETE FROM anime_tag WHERE anime_id >= " + SUBJECT_BASE);
        jdbc.execute("DELETE FROM anime_tag WHERE tag_id IN (SELECT id FROM tag WHERE name LIKE 'qct-%')");
        jdbc.execute("DELETE FROM tag WHERE name LIKE 'qct-%'");
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
        seedReviews(n, i -> 8);
    }

    /**
     * 同上, 但分数由调用方按行号决定.
     *
     * <p>评分统计那组要的是「十档都有」—— 只有分数铺开了, 均分与分布才有东西可断言;
     * 全给 8 分的话, 分布数组里九个零一个 n, 算错了也未必看得出来.
     */
    private void seedReviews(int n, java.util.function.IntUnaryOperator ratingOf) {
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
                            + "VALUES (?, ?, ?, ?, ?)",
                    author.getId(), SUBJECT_BASE, ratingOf.applyAsInt(i), "c" + i,
                    new java.sql.Timestamp(base + i * 60_000L));
        }
    }

    /**
     * 清空统计并把 Statistics 交出来.
     *
     * <p>只数语句条数的用例走 {@link #statementsFor}; 需要看"读了几行"的那些
     * (下面两组)自己拿这个, 因为 {@code getEntityLoadCount()} 与语句条数是两个问题.
     */
    private Statistics statsCleared() {
        Statistics stats = entityManagerFactory.unwrap(SessionFactory.class).getStatistics();
        stats.clear();
        return stats;
    }

    /** 只数这次动作真正发出去的语句. 灌数据的那几条在 clear() 之前, 不计入. */
    private long statementsFor(Runnable action) {
        Statistics stats = statsCleared();
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

    // ========== 评分统计 ==========
    //
    // 这一组与上面两组不同, 度量的是**读进来多少个实体**, 而不是发了几条 SQL.
    //
    // 因为「把该番的评论全读回来在内存里数」与「让数据库 GROUP BY」这两种写法,
    // 语句条数完全一样(都是一条 SELECT), 返回的三个数字也一模一样 ——
    // 只数语句分不出它们. 分得开的是读入行数: 前者随评论条数线性增长,
    // 后者恒为 0(投影不是实体), 这正是这次改动全部的意义所在.

    @ParameterizedTest(name = "{0} 条评论")
    @ValueSource(ints = {10, 50})
    @DisplayName("评分统计: 均分/条数/十档分布都对, 且一个评论实体都没读进内存")
    void ratingStatsAggregatesInTheDatabase(int n) {
        // 1~10 分轮着来: n=10 时每档 1 条, n=50 时每档 5 条, 两种规模的均分都是 5.5 ——
        // 数据量翻五倍而结果不变, 才是"与评论条数无关"该有的样子
        seedReviews(n, i -> i % 10 + 1);
        assertThat(jdbc.queryForObject(
                "SELECT COUNT(*) FROM review WHERE subject_id = " + SUBJECT_BASE, Integer.class))
                .as("评论要真的灌进去了, 否则下面断言的是空库")
                .isEqualTo(n);

        Statistics stats = statsCleared();
        Map<String, Object> result = reviewService.getRatingStats(SUBJECT_BASE);

        assertThat(result.get("count")).isEqualTo((long) n);
        assertThat(result.get("average")).isEqualTo(5.5);
        assertThat((int[]) result.get("distribution"))
                .containsExactly(n / 10, n / 10, n / 10, n / 10, n / 10,
                        n / 10, n / 10, n / 10, n / 10, n / 10);
        assertThat(stats.getPrepareStatementCount()).as("一条 GROUP BY 问到底").isEqualTo(1);
        assertThat(stats.getEntityLoadCount())
                .as("改动前这里等于评论条数(%d) —— 统计不该把评论本身读出来", n)
                .isZero();
        assertThat(SqlRecorder.last())
                .as("聚合是数据库做的, 生成的 SQL 里得真有 GROUP BY")
                .containsIgnoringCase("group by");
    }

    /**
     * 一条评论都没有的番 —— 真 SQL 的 GROUP BY 这时返回空集, 是另一条路径
     * (内存遍历那版对空库同样不会出错, 所以这条不是在防改动本身, 是在防
     * "把空集当成异常"或"除零"这类新写法).
     */
    @Test
    @DisplayName("没人评分的番: 均分 0、条数 0、十档全 0")
    void ratingStatsOnASubjectWithNoReviews() {
        seedReviews(3, i -> 9);

        Map<String, Object> result = reviewService.getRatingStats(SUBJECT_BASE + 999);

        assertThat(result.get("count")).isEqualTo(0L);
        assertThat(result.get("average")).isEqualTo(0.0);
        assertThat((int[]) result.get("distribution")).hasSize(10).containsOnly(0);
    }

    // ========== 按标签查番剧 ==========
    //
    // 这一组换了一个度量: **读进来多少个实体**, 而不是发了几条 SQL.
    //
    // 因为"整表进内存再 filter"和"走索引"这两种写法, 语句条数可以一样多 ——
    // 前者就是两条 SELECT(一条读 tag 全表, 一条读 anime_tag 全表), 后者也是两条.
    // 光数语句分不出它们, 只有"读了几行"能: 改前是 tag 全表 + anime_tag 全表
    // 都进内存, 几千行就是几千个实体. 所以这里断言的是 entityLoadCount,
    // 并且断言它与噪声行数**无关**(100 行和 2000 行读到的一样多).

    /** 噪声关联行用的 id 段, 与追番/评论那一段错开 */
    private static final int TAG_NOISE_BASE = 96100000;

    /** 取一个标签行的 id, 没有就建. 预加载器与标签迁移都会建同名标签, 所以不能直接 INSERT */
    private long tagIdOf(String name) {
        List<Long> existing = jdbc.queryForList("SELECT id FROM tag WHERE name = ?", Long.class, name);
        if (!existing.isEmpty()) {
            return existing.get(0);
        }
        jdbc.update("INSERT INTO tag (name) VALUES (?)", name);
        return jdbc.queryForObject("SELECT id FROM tag WHERE name = ?", Long.class, name);
    }

    /** 灌 n 行与本次查询无关的关联行, 返回它们挂的那个标签 id */
    private long seedAnimeTagNoise(int rows) {
        long tagId = tagIdOf("qct-noise-" + rows);
        List<Object[]> args = new ArrayList<>();
        for (int i = 0; i < rows; i++) {
            args.add(new Object[]{TAG_NOISE_BASE + i, tagId});
        }
        jdbc.batchUpdate("INSERT INTO anime_tag (anime_id, tag_id) VALUES (?, ?)", args);
        return tagId;
    }

    /** 建一部番剧并挂到一个标签上 */
    private void seedTaggedAnime(int id, String title, String date, long tagId) {
        jdbc.update("INSERT INTO anime (id, title, date) VALUES (?, ?, ?)", id, title, date);
        jdbc.update("INSERT INTO anime_tag (anime_id, tag_id) VALUES (?, ?)", id, tagId);
    }

    /**
     * 关联表长到几千行时, 按标签查番剧读进来的仍然只有命中的那几行.
     *
     * <p>改动前这里是: 读 tag 全表(几千行? 不, 几十行) + 读 anime_tag **全表**
     * 再在内存里过滤, 所以噪声行数会被原样算进读入量 —— 这条用例的两种数据量
     * 断言的是同一个数字, 正是为了说明"读入量与表的大小无关".
     */
    @ParameterizedTest(name = "关联表里另有 {0} 行噪声")
    @ValueSource(ints = {100, 2000})
    @DisplayName("按标签查番剧: 读入的实体数与关联表行数无关(1 个标签 + 3 部番剧)")
    void tagLookupNeverLoadsTheWholeJoinTable(int noiseRows) {
        seedAnimeTagNoise(noiseRows);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM anime_tag WHERE anime_id >= "
                + TAG_NOISE_BASE, Integer.class))
                .as("噪声行要真的灌进去了, 否则这条用例是空过")
                .isEqualTo(noiseRows);

        String target = "qct-target-" + noiseRows;
        long targetTag = tagIdOf(target);
        for (int i = 0; i < 3; i++) {
            seedTaggedAnime(SUBJECT_BASE + i, "标签番" + i, String.format("2024-01-0%d", i + 1), targetTag);
        }

        Statistics stats = entityManagerFactory.unwrap(SessionFactory.class).getStatistics();
        stats.clear();
        List<Anime> found = animeService.getByTags(Set.of(target));
        long loadedEntities = stats.getEntityLoadCount();

        assertThat(found).extracting(Anime::getId)
                .containsExactly(SUBJECT_BASE + 2, SUBJECT_BASE + 1, SUBJECT_BASE);
        assertThat(loadedEntities)
                .as("读入 1 个 tag 实体 + 3 部番剧; 改动前这里还要加上 %d 行噪声", noiseRows)
                .isEqualTo(4);
    }

    /**
     * 中文标签名会带着它的英文写法一起来查("百合" → 中文名 + Yuri + Girls Love),
     * 同一部番挂了两个名字时只该出现一次.
     *
     * <p>这条用的是真实存在的中文标签名, 所以库里(s)可能还有预加载器灌的其他番剧 ——
     * 断言只在自己建的这几行上做, 但**去重**那一条是对整个结果断言的:
     * 重复是这次改动的直接后果(并集 + 两次命中同一部番), 与谁的数据无关.
     *
     * <p>日期给到 2099 年是为了避开封顶: 真实标签下的番剧可能不止 50 部,
     * 而按播出日倒序截断时, 自建的这几行要稳稳排在最前面.
     */
    @Test
    @DisplayName("标签的多种写法是并集且去重: 中文名与英文名各命中的番剧都在, 且不重复")
    void tagNamesAreUnionAndDeduplicated() {
        long cn = tagIdOf("百合");
        long en = tagIdOf("Yuri");
        seedTaggedAnime(SUBJECT_BASE, "只挂中文名", "2099-01-03", cn);
        seedTaggedAnime(SUBJECT_BASE + 1, "只挂英文名", "2099-01-02", en);
        seedTaggedAnime(SUBJECT_BASE + 2, "两个名字都挂", "2099-01-01", cn);
        jdbc.update("INSERT INTO anime_tag (anime_id, tag_id) VALUES (?, ?)", SUBJECT_BASE + 2, en);

        Set<String> names = TagTranslationUtil.reverseTranslateAll("百合");
        assertThat(names).as("这个标签确实有多种写法, 否则这条用例测不到并集").hasSizeGreaterThan(1);

        List<Anime> found = animeService.getByTags(names, AnimeService.BY_TAG_LIMIT);
        List<Integer> mine = found.stream().map(Anime::getId)
                .filter(id -> id >= SUBJECT_BASE).toList();

        assertThat(mine).containsExactlyInAnyOrder(SUBJECT_BASE, SUBJECT_BASE + 1, SUBJECT_BASE + 2);
        assertThat(found).extracting(Anime::getId)
                .as("同一部番被两个标签名各命中一次, 只能出现一遍")
                .doesNotHaveDuplicates();
    }

    /**
     * 标签下超过上限时, 留下的是播出日最近的 50 部.
     *
     * <p>上限与"不封顶"的差别在返回条数上; "先截断"与"先排序"的差别只在留下哪几部 ——
     * 所以两个断言都要: 条数是 50, 而留下的确实是最近的那 50 部.
     */
    @Test
    @DisplayName("标签下 60 部: 公开上限只返回最近的 50 部, 不封顶的那个重载返回 60")
    void tagBrowseIsCappedAndKeepsTheNewestOnes() {
        long tagId = tagIdOf("qct-many");
        for (int i = 0; i < 60; i++) {
            seedTaggedAnime(SUBJECT_BASE + i, "量产番" + i,
                    String.format("%04d-01-01", 1960 + i), tagId);
        }

        List<Anime> capped = animeService.getByTags(Set.of("qct-many"), AnimeService.BY_TAG_LIMIT);

        assertThat(capped).hasSize(AnimeService.BY_TAG_LIMIT);
        assertThat(capped.get(0).getId()).isEqualTo(SUBJECT_BASE + 59);
        assertThat(capped).extracting(Anime::getId)
                .doesNotContain(SUBJECT_BASE, SUBJECT_BASE + 9)
                .contains(SUBJECT_BASE + 10);
        // 筛选接口走的是不封顶的那个: 它拿到完整集合后还要按年份/季度/状态再筛,
        // 在这里截断会让"符合条件"的行凭空消失
        assertThat(animeService.getByTags(Set.of("qct-many"))).hasSize(60);
    }

    /**
     * "关联表空不空"这条判定本身也不该随表长变慢.
     *
     * <p>它原来写的是 {@code SELECT COUNT(*) > 0 FROM anime_tag}, 每次按标签查番剧
     * 都要先数一遍整张表. 现在改成 exists 派生查询, 依赖的是 Spring Data 对 exists
     * 投影会加 {@code setMaxResults(1)} —— 这是框架行为, 不是我们写的 SQL,
     * 所以这里直接对生成的语句下断言: 哪天升级把它改掉了, 这条会先红,
     * 而不是悄悄退化成"每次数一遍整张表".
     *
     * <p>{@code fetch first} 是 H2 方言对行数限制的写法(实测; 这个用例跑在 H2 上).
     */
    @Test
    @DisplayName("判断关联表是否为空: 生成的 SQL 带行数限制, 不是 COUNT 整表")
    void emptinessCheckIsLimitedToOneRow() {
        seedAnimeTagNoise(2000);

        boolean any = animeTagRepository.existsByAnimeIdNotNull();

        assertThat(any).isTrue();
        assertThat(SqlRecorder.last())
                .as("这条判定的答案在第一行就定了, 不该数完整张表")
                .containsIgnoringCase("from anime_tag")
                .containsIgnoringCase("fetch first");
    }

    /**
     * 关联表里有指向已删除番剧的行时, 结果里不该多出东西.
     *
     * <p>{@code anime_tag} 与 {@code anime} 之间不是外键强约束, 这类悬挂行是会出现的
     * (删除番剧时只删了主表). 改成半连接(现在是 {@code a.id IN (子查询)}, 见
     * {@code AnimeQueries.TAG_MATCHES})之后这件事是白送的 ——
     * 子查询要求 {@code anime} 里真有那一行才算命中, 而改前那个版本是"先取一批
     * anime_id 再按 id 取番剧", 靠的是批量查的天然宽容. 断言留下, 是因为换写法时
     * 这一点会被无声地换掉.
     */
    @Test
    @DisplayName("悬挂的关联行(番剧已删)不会带出多余的行")
    void danglingTagRowsDoNotProduceRows() {
        long tagId = tagIdOf("qct-dangling");
        seedTaggedAnime(SUBJECT_BASE, "还在", "2099-01-01", tagId);
        jdbc.update("INSERT INTO anime_tag (anime_id, tag_id) VALUES (?, ?)",
                SUBJECT_BASE + 999, tagId);

        assertThat(animeService.getByTags(Set.of("qct-dangling")))
                .extracting(Anime::getId).containsExactly(SUBJECT_BASE);
    }

    // ========== 排序 / 筛选 / 分页下推之后: 读进来几行 ==========
    //
    // 这一组是本批次的重点. 改动前六条读路径都是"整张 anime 表进内存再排/再筛/再切",
    // 读入量随表长线性增长 —— 而返回值一个字节都不差, 所以断言返回值完全看不出它.
    // 近三万条时, 每一次请求都要真的搬三万行过来, 这正是这一遍要修的东西.
    //
    // 断言写成"读入量 == 页大小"而不是"== 某个具体数字": 前者在库里 25 行和 200 行
    // 时是同一个数, 后者只是恰好等于某个常量.

    /** 一页取多少条. 比 @Min 大、比噪声行数小, 越界与"整表"都能区分开 */
    private static final int PAGE = 10;

    /** 灌 n 部有多人评分的番剧 —— 排行榜那条路要求 rating 与 rating_count 都有效 */
    private void seedRatedAnime(int n) {
        List<Object[]> args = new ArrayList<>();
        for (int i = 0; i < n; i++) {
            args.add(new Object[]{SUBJECT_BASE + i, "评分番" + i, 9.0, 1000 + i});
        }
        jdbc.batchUpdate("INSERT INTO anime (id, title, rating, rating_count) VALUES (?, ?, ?, ?)", args);
    }

    /**
     * 排行榜读进来的行数封在一页上, 与表里有多少行无关.
     *
     * <p>为什么行数取 25 与 200 而不是更小: {@code getRanking} 在库存不足
     * {@code min(limit,20)} 条时会去回源补数据(真的打 api.bgm.tv). 25 是最小的
     * 安全值, 而两档相差 8 倍已经足够说明"与表长无关".
     */
    @ParameterizedTest(name = "库里 {0} 部番剧")
    @ValueSource(ints = {25, 200})
    @DisplayName("排行榜: 读入的实体数封在 limit 上, 与库里有几行无关")
    void rankingLoadsOnlyOnePage(int rows) {
        seedRatedAnime(rows);
        // 这一条走 @Cacheable, 两档用的是同一个 key —— 不清缓存的话第二次会直接命中
        // 缓存, 读入行数变成 0 而断言红, 且红得毫无道理
        cacheManager.getCache("ranking").clear();

        Statistics stats = statsCleared();
        List<Anime> top = animeService.getRanking(PAGE);

        assertThat(top).hasSize(PAGE);
        assertThat(stats.getEntityLoadCount())
                .as("改动前这里等于 %d —— 整张榜都要读进来才排得出前 %d 名", rows, PAGE)
                .isEqualTo(PAGE);
    }

    /** 灌 n 部属于同一个季度值的番剧. 季度是自造的, 于是"匹配到多少条"完全由本用例说了算 */
    private void seedSeasonedAnime(int n, String season) {
        List<Object[]> args = new ArrayList<>();
        for (int i = 0; i < n; i++) {
            args.add(new Object[]{SUBJECT_BASE + i, "季度番" + i, season,
                    String.format("2024-01-%02d", i % 28 + 1), i});
        }
        jdbc.batchUpdate("INSERT INTO anime (id, title, season, date, sort_rank) VALUES (?, ?, ?, ?, ?)", args);
    }

    /**
     * 筛选读进来的行数封在一页上, 与匹配到多少条无关.
     *
     * <p>这条同时钉住 {@code :param IS NULL} 那套可选谓词真的能在 H2 上跑 ——
     * 语句里三个条件各带一个 null 绑定参数, 而 {@code LIKE :yearPattern ESCAPE '!'}
     * 那一条最容易被绑定类型搞坏(参数为 null 时库推不出类型). 这里 year 与 status
     * 都传 null, 正好走到那条路.
     */
    @ParameterizedTest(name = "匹配 {0} 条")
    @ValueSource(ints = {25, 200})
    @DisplayName("筛选: 读入的实体数封在一页上, 与匹配到多少条无关")
    void filterLoadsOnlyOnePage(int rows) {
        String season = "9q63-01";
        seedSeasonedAnime(rows, season);

        Statistics stats = statsCleared();
        Map<String, Object> result =
                animeService.getFilteredPage(null, season, null, null, null, 1, PAGE);

        @SuppressWarnings("unchecked")
        List<Anime> page = (List<Anime>) result.get("list");
        assertThat(page).hasSize(PAGE);
        assertThat(result.get("total")).as("total 报的是匹配总数, 不是页大小").isEqualTo(rows);
        assertThat(stats.getEntityLoadCount())
                .as("改动前这里等于 %d —— 先整表进内存再 filter", rows)
                .isEqualTo(PAGE);
    }

    /**
     * 按标签浏览读进来的行数封在 {@code BY_TAG_LIMIT} 上.
     *
     * <p>留一行的余量给 tag 实体: 标签名要先解析成 id, 那一步会 load 一个 tag 行
     * (改前也一样, 所以断言的数字与改前是同一个量级 —— 变的是番剧那一半).
     */
    @ParameterizedTest(name = "标签下 {0} 部")
    @ValueSource(ints = {25, 200})
    @DisplayName("按标签浏览: 读入的番剧数封在 50 上, 与标签下有多少部无关")
    void tagBrowseLoadsOnlyOnePage(int rows) {
        String name = "qct-page-" + rows;
        long tagId = tagIdOf(name);
        for (int i = 0; i < rows; i++) {
            seedTaggedAnime(SUBJECT_BASE + i, "标签番" + i,
                    String.format("%04d-01-01", 1900 + i), tagId);
        }

        Statistics stats = statsCleared();
        List<Anime> page = animeService.getByTags(Set.of(name), AnimeService.BY_TAG_LIMIT);

        int pageSize = Math.min(rows, AnimeService.BY_TAG_LIMIT);
        assertThat(page).hasSize(pageSize);
        assertThat(stats.getEntityLoadCount())
                .as("读入 1 个 tag 实体(标签名 → id) + %d 部番剧; 改动前这里要加上 %d",
                        pageSize, rows - pageSize)
                .isEqualTo(pageSize + 1);
    }

    // ========== 生成的 SQL 长什么样 ==========
    //
    // 条数与行数都看不见"这条语句有没有把条件真的下推" —— 一条 WHERE 什么都没筛的
    // SELECT 也是一条语句、读进来的行数也由别的东西决定. SQL 形状是唯一能抓住
    // "常量拼错了、拼漏了"的地方: 那种错会让查询静默退化成另一条语义, 接口照常 200.

    /** 行数限制在 H2 方言里是 fetch first; 断言前把空白折平, 免得被换行/多空格绊倒 */
    private static String lastSqlNormalized() {
        return SqlRecorder.last().replaceAll("\\s+", " ");
    }

    /**
     * 不带标签的筛选: 生成的 SQL 里**不该有** {@code anime_tag}.
     *
     * <p>为什么要断言"没有"这种东西: 把标签那个 {@code EXISTS} 子查询误拼进
     * 不带标签的那三条语句里, 结果会变成"只返回有标签的番剧" —— 一个不报错、
     * 只是少一大半数据的 bug.
     */
    @Test
    @DisplayName("筛选(不带标签)的 SQL: 不碰 anime_tag, 排序是 CASE 分组, 且带行数限制")
    void untaggedFilterSqlIsPushedDown() {
        seedSeasonedAnime(3, "9q63-02");

        animeRepository.findFilteredByDate(null, "9q63-02", null, PageRequest.of(0, 5));
        String sql = lastSqlNormalized();

        assertThat(sql).containsIgnoringCase("from anime")
                .as("这条路上不该出现标签关联表").doesNotContainIgnoringCase("anime_tag");
        assertThat(sql).as("缺日期的行要显式分组, 否则 NULL 排哪随库变")
                .containsIgnoringCase("case when");
        assertThat(sql).as("分页必须是库做的, 不是读回来再切")
                .containsIgnoringCase("fetch first");
    }

    /**
     * 按标签浏览: 用 {@code IN} 子查询半连接, 既不是顶层 JOIN, 也不是 {@code EXISTS}.
     *
     * <p>顶层 JOIN 会让同时挂在"百合"和"Yuri"两个名字下的同一部番出现两次,
     * {@code total} 因此虚高, LIMIT/OFFSET 也会去数这些重复行 —— 翻页时相邻两页
     * 重叠、末尾几行永远看不到.
     *
     * <p><b>为什么连 {@code EXISTS} 也不许出现.</b> 它和 {@code IN} 子查询语义完全等价,
     * 返回的行、读入的实体数一个都不差 —— 差别只在 H2 怎么排这个连接: {@code EXISTS}
     * 排成了对 {@code anime} 的全表扫描 + 逐行拿主键回探 {@code anime_tag}, 满库时
     * 同一个请求要 124 s, 而 {@code IN} 子查询是 0.90 s(完整的实测见
     * {@code AnimeQueries.TAG_MATCHES} 的注释). 两种写法从返回值到行数到实体数
     * 全都一模一样, **只有 SQL 文本分得出来** —— 所以这条断言是这个坑唯一的哨兵.
     */
    @Test
    @DisplayName("按标签浏览的 SQL: 是 IN 子查询半连接, 不是顶层 JOIN 也不是 EXISTS")
    void tagBrowseSqlUsesInSubquery() {
        seedTaggedAnime(SUBJECT_BASE, "标签番", "2099-01-01", tagIdOf("qct-shape"));

        animeService.getByTags(Set.of("qct-shape"));
        String sql = lastSqlNormalized();

        assertThat(sql).containsIgnoringCase("from anime_tag")
                .as("半连接子查询里出现关联表是对的; 不该出现的是顶层 join")
                .doesNotContainIgnoringCase("join anime_tag");
        assertThat(sql).as("要的是 a.id in (select ...) —— 与外层没有任何关联列的子查询")
                .containsIgnoringCase("id in (select");
        assertThat(sql).as("EXISTS 那条写法在 H2 上是全表扫描 + 逐行主键回探, 满库时慢 138 倍")
                .doesNotContainIgnoringCase("exists");
    }

    /**
     * 名次排序只在 {@code sort=rank} 那条路上, 且同样是 CASE 分组 ——
     * 名次为 NULL 的行(库里占多数)必须排在有名次的之后, 而不是按库的默认 NULL 位置.
     */
    @Test
    @DisplayName("sort=rank 的 SQL: 名次为 NULL 的行显式分组排最后")
    void rankSortGroupsNullExplicitly() {
        seedSeasonedAnime(3, "9q63-03");

        animeRepository.findFilteredByRank(null, "9q63-03", null, PageRequest.of(0, 5));
        String sql = lastSqlNormalized();

        assertThat(sql).containsIgnoringCase("case when")
                .containsIgnoringCase("sort_rank");
    }

    /**
     * 年份筛选走的是<b>转义过的前缀 LIKE</b>, 不是通配符.
     *
     * <p>改动前是 {@code date.startsWith(year)} —— 字面前缀. 搬进 LIKE 之后
     * {@code %} 与 {@code _} 会从字面量变成通配符, 于是 {@code year=20%} 从
     * "没有这种年份"变成"匹配全部". 这条断言的是转义字符真的在语句里
     * ({@code escape}), 而"转义有没有生效"由下面那条用例用数据验.
     */
    @Test
    @DisplayName("年份筛选的 SQL: LIKE 带 ESCAPE, 不是裸 LIKE")
    void yearFilterEscapesLikeWildcards() {
        seedSeasonedAnime(3, "9q63-04");

        animeRepository.findFilteredByDate(
                SearchPatterns.prefix("20%"), null, null, PageRequest.of(0, 5));
        String sql = lastSqlNormalized();

        assertThat(sql).containsIgnoringCase("like").containsIgnoringCase("escape");
    }

    /**
     * 年份里的 {@code %} 与 {@code _} 是字面量, 不是通配符.
     *
     * <p>这一条用**数据**验转义: 库里放一部 2024 年的番, 然后按年份 {@code "20%"}
     * 去筛. 转义失效时 {@code 20%} 会变成"所有 20 开头的", 那部 2024 年的就会被
     * 命中 —— 而用户说的是"年份就是 20% 这个字符串", 应该一条都不匹配.
     */
    @Test
    @DisplayName("年份里的 % 是字面量: date=2024-… 不该被 year=20% 命中")
    void percentInYearIsALiteralNotAWildcard() {
        seedSeasonedAnime(3, "9q63-05");

        Map<String, Object> matched = animeService.getFilteredPage(
                "20%", "9q63-05", null, null, null, 1, 20);

        assertThat(matched.get("total"))
                .as("转义一旦失效, 这个数字会变成 3(全部命中)")
                .isEqualTo(0);
    }

    /**
     * 页码溢出不能退化成"从负数开始取一页".
     *
     * <p>{@code (page-1)*limit} 在 int 里会溢出成负数; 切片下推之后它交给
     * {@code Query.setFirstResult(int)}, 于是负起点会被真的发给数据库 ——
     * 两个库对它的反应既不统一也不报错. service 里那个 long 守卫就是为这一条.
     */
    @Test
    @DisplayName("page=Integer.MAX_VALUE: 返回空页, 但 total 仍是真实的匹配数")
    void hugePageNumberStillReportsTheRealTotal() {
        String season = "9q63-06";
        seedSeasonedAnime(3, season);

        Map<String, Object> result = animeService.getFilteredPage(
                null, season, null, null, null, Integer.MAX_VALUE, 20);

        assertThat((List<?>) result.get("list")).isEmpty();
        assertThat(result.get("total"))
                .as("越界页也要报真实总数, 否则前端的翻页控件会凭空少几页")
                .isEqualTo(3);
    }
}
