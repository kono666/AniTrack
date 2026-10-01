package com.animetracker.service;

import com.animetracker.agent.tool.ToolRegistry;
import com.animetracker.entity.Anime;
import com.animetracker.entity.User;
import com.animetracker.exception.BusinessException;
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
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.cache.CacheManager;
import org.springframework.data.domain.PageRequest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.Deque;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentLinkedDeque;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

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

        /** 最近若干条, 给 {@link #lastContaining} 用. 有上限, 免得整类跑下来无限长 */
        private static final Deque<String> RECENT = new ConcurrentLinkedDeque<>();
        private static final int RECENT_LIMIT = 64;

        @Override
        public String inspect(String sql) {
            LAST.set(sql);
            RECENT.addLast(sql);
            while (RECENT.size() > RECENT_LIMIT) {
                RECENT.pollFirst();
            }
            return sql;
        }

        static String last() {
            return LAST.get();
        }

        /**
         * 最近这批语句里**最后一条包含 {@code needle} 的**.
         *
         * <p><b>为什么 {@link #last()} 在这条路上不够用。</b>一次 {@code getReviewPage}
         * 发的不止一条语句: count、取页、以及**取页之后**还有一条给行补番剧名的批量查询
         * (见 {@code AdminService.toAdminReviewRows})。所以 {@code last()} 拿到的是那条
         * 批量查询, 不是取页 SQL —— 而取页 SQL 恰恰是这里唯一想看的东西。
         * 页为空时没有那条批量查询, {@code last()} 又碰巧是对的: 断言于是变成
         * "看这一页有没有数据" 才决定查的是哪条语句, 是最难查的那种假绿。
         *
         * <p>按内容挑而不是按序号挑: 序号要人去数"这次多发了哪一条", 而数错了的表现
         * 是拿到一条别的语句去断言 —— 一条永远为真的断言。
         */
        static String lastContaining(String needle) {
            String found = null;
            for (String sql : RECENT) {
                if (sql.contains(needle)) {
                    found = sql;
                }
            }
            return found;
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
    private ReviewReplyService reviewReplyService;
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
    /** 助手侧那些工具: 它们读的是**全量**口径, 而分页那几条接口是另一个口径 */
    @Autowired
    private ToolRegistry toolRegistry;

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

        long statements = statementsFor(() -> reviewService.getSubjectReviews(0L, SUBJECT_BASE, 1, 20, null));

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

        List<String> firstPage = contentsOf(reviewService.getSubjectReviews(0L, SUBJECT_BASE, 1, 5, null));
        List<String> secondPage = contentsOf(reviewService.getSubjectReviews(0L, SUBJECT_BASE, 2, 5, null));

        // c9 是最晚写的, 倒序排第一
        assertThat(firstPage).containsExactly("c9", "c8", "c7", "c6", "c5");
        assertThat(secondPage).containsExactly("c4", "c3", "c2", "c1", "c0");
    }

    private static List<String> contentsOf(List<Map<String, Object>> reviews) {
        return reviews.stream().map(m -> (String) m.get("content")).toList();
    }

    /**
     * 登录之后多出来的**恰好一条**: 批量问"这一页里我赞过哪些".
     *
     * <p>为什么把它和匿名那条分开断言而不是合成一条参数化: 两者的数字不一样
     * (2 与 1), 而**差异本身就是这次改动的设计**—— 匿名的 {@code userId=0} 下
     * "有没有赞过"对谁都恒为 false, 那条查询问不出任何东西, 所以整条不发。
     * 只断言登录态是 2 的话, 看不出"匿名没多花"这件事; 只断言匿名是 1 的话,
     * 又看不出登录态确实查了。
     *
     * <p>更要紧的是把条数**钉死在 2**: 逐条查会是 1+N, 而这是最容易被下一个人
     * "顺手改成更好懂的写法"的地方。
     */
    @Test
    @DisplayName("评论列表: 登录态是 2 次查询(列表 + 批量查我赞过哪些), 与条数无关")
    void reviewListCostsTwoQueriesForALoggedInUser() {
        seedReviews(5);

        long statements = statementsFor(() -> reviewService.getSubjectReviews(
                user.getId(), SUBJECT_BASE, 1, 20, null));

        assertThat(statements).describedAs("列表 1 条 + likedByMe 批量 1 条").isEqualTo(2);
    }

    /**
     * 一部**一条评论都没有**的番, 评论列表仍然是 1 条语句 —— 登录态也一样。
     *
     * <p>这条钉的是 {@code ReviewService.likedReviewIds} 里那个
     * {@code reviews.isEmpty()} 提前返回。没有它, 空的 id 集合会被送进 JPQL 的
     * {@code IN}, 而空集合在 JPQL 里没有合法写法(Hibernate 6 恰好把它渲染成
     * {@code 1=0}, 但那是它的实现选择, 不是语言保证)。
     *
     * <p>这条断言确实分得开这两种写法, 而且实测过: 把守卫改成 {@code || false} 之后
     * 这条会红在 {@code isEqualTo(1)} 上 —— 少这一道守卫, 那句 {@code IN} 查询**照样会被
     * 发出去**, 于是"一条评论都没有的番"反而比有评论时多花一条语句。至于 Hibernate 把
     * 空集合渲染成什么(它恰好渲染成 {@code 1=0}), 这条断言不依赖 —— 守卫的价值就是
     * 那条语句根本不发, 与它长什么样无关。这与 {@code AnimeQueries} 那个哨兵参数守的是
     * 同一处边界。
     */
    @Test
    @DisplayName("没有评论的番: 登录态下也不多发查询, 更不会因为空 IN 报错")
    void reviewListOfASubjectWithNoReviewsIsStillOneQuery() {
        long statements = statementsFor(() -> reviewService.getSubjectReviews(
                user.getId(), SUBJECT_BASE, 1, 20, null));

        assertThat(statements).isEqualTo(1);
    }

    /**
     * 按热度翻页拿到的确实是下一批, 而且**顺序真的由赞数决定**。
     *
     * <p>数据是**反着**灌的: 赞数与插入顺序相反(c0 赞最多, c9 最少)。这样热度序
     * 恰好是时间序的倒序 —— 如果 {@code sort=hot} 被忽略、退回默认的时间序,
     * 或者 ORDER BY 写成了升序, 这条都会红, 而"两个序碰巧一样"的假绿就不会出现。
     *
     * <p>这同时是唯一能证明热度序**排序做在数据库里**的断言: 若改成"全读出来在
     * 内存里排", 语句条数一样是 1, 只有"拿回来的是哪几条"能分开。
     */
    @Test
    @DisplayName("热度序: 真的按赞数排, 且翻页切在数据库上")
    void reviewListHotOrderSlicesInTheDatabase() {
        seedReviewsWithDescendingLikes(10);

        List<String> firstPage = contentsOf(reviewService.getSubjectReviews(
                0L, SUBJECT_BASE, 1, 5, ReviewService.SORT_HOT));
        List<String> secondPage = contentsOf(reviewService.getSubjectReviews(
                0L, SUBJECT_BASE, 2, 5, ReviewService.SORT_HOT));

        assertThat(firstPage).containsExactly("c0", "c1", "c2", "c3", "c4");
        assertThat(secondPage).containsExactly("c5", "c6", "c7", "c8", "c9");
    }

    /**
     * 热度序的 SQL 里, 赞数并列之后那一键显式处理了 NULL, 且切片仍在库里。
     *
     * <p><b>为什么这条必须存在: 上面那条语义用例守不住它。</b>把 {@code ORDER BY} 里
     * {@code CASE WHEN created_at IS NULL …} 那一段整个抹掉之后, 上面那条断言的
     * 第一页/第二页**逐行不变**, 因为 H2 本来就把 NULL 当最小值排在 DESC 的最后,
     * 与那个 CASE 分出来的组完全一致 —— 实测整个后端套件 607 条全绿, 一条都不红。
     * 真正有差别的是 PG(DESC 下把 NULL 当最大值排最前), 而线上 PG 不在本地验证的
     * 射程内。这与 {@code AnimeQueries}、以及下面
     * {@link #userPageSqlIsPushedDownWithNullSafeOrdering} 记的是同一条限制:
     * <b>SQL 文本是这种坑唯一的哨兵</b>, 条数与返回值都看不出来。
     *
     * <p>少了这一键的后果不是"排得难看"而是<b>翻页漏行</b>: 排序后面跟着 LIMIT/OFFSET,
     * 两行并列时谁在前随库而定, 于是第 1 页的最后一条与第 2 页的第一条可能互换,
     * 用户翻页时看到同一条评论两次, 而另一条永远翻不到。
     */
    @Test
    @DisplayName("热度序的 SQL: 并列之后显式分组 NULL, 且行数限制在库里")
    void hotOrderSqlIsPushedDownWithNullSafeOrdering() {
        seedReviews(3);

        reviewService.getSubjectReviews(0L, SUBJECT_BASE, 1, 5, ReviewService.SORT_HOT);
        String sql = lastSqlNormalized();

        assertThat(sql).containsIgnoringCase("order by")
                .containsIgnoringCase("like_count desc")
                .as("缺时间的行要显式分组, 否则 NULL 排哪随库变, 第 2 页就会混进第 1 页的行")
                .containsIgnoringCase("case when")
                .as("第二键要换成常量, 让 'ORDER BY 里没有 NULL' 字面成立")
                .containsIgnoringCase("coalesce")
                .as("分页必须是库做的, 不是读回来再切")
                .containsIgnoringCase("fetch first");
    }

    /**
     * 未知的 sort 值退化成默认的时间序, 不报错也不返回空。
     *
     * <p>与 {@code AdminService.getUserPage} 对 role/status/order 的处理是同一条规矩:
     * 排序是展示偏好, 而 {@code sort} 会出现在用户分享出去的链接里 —— 为一个拼错的
     * 值让整个评论列表打不开, 代价远大于按默认序显示。
     */
    @Test
    @DisplayName("未知的 sort 值走默认时间序, 不是空列表也不是异常")
    void unknownSortFallsBackToTheDefaultOrder() {
        seedReviews(3);

        List<String> byUnknown = contentsOf(reviewService.getSubjectReviews(
                0L, SUBJECT_BASE, 1, 20, "sortByVibes"));
        List<String> byDefault = contentsOf(reviewService.getSubjectReviews(
                0L, SUBJECT_BASE, 1, 20, null));

        assertThat(byUnknown).isEqualTo(byDefault).containsExactly("c2", "c1", "c0");
    }

    /**
     * 同 {@link #seedReviews(int)}, 但赞数与插入顺序**相反**: c0 赞最多。
     *
     * <p>反着来是为了让热度序与时间序给出不同的答案 —— 只有两序不同,
     * "热度序真的生效了"才断言得出来。
     */
    private void seedReviewsWithDescendingLikes(int n) {
        long base = java.sql.Timestamp.valueOf("2030-01-01 00:00:00").getTime();
        for (int i = 0; i < n; i++) {
            User author = userRepository.save(User.builder()
                    .username("h" + UUID.randomUUID().toString().substring(0, 12))
                    .password("x")
                    .role("USER")
                    .status("ACTIVE")
                    .build());
            jdbc.update("INSERT INTO review (user_id, subject_id, rating, content, created_at, like_count) "
                            + "VALUES (?, ?, ?, ?, ?, ?)",
                    author.getId(), SUBJECT_BASE, 8, "c" + i,
                    new java.sql.Timestamp(base + i * 60_000L), (long) (n - 1 - i));
        }
    }

    // ========== 管理端评论列表 ==========
    //
    // 这条路以前是 getAllReviews(): Pageable.unpaged(), 整张评论表进 JVM 再原样塞进
    // 一个 JSON 数组, 而两个 AI 工具各取全表只为了截前 30 / 15 条. 改成分页之后
    // 「一条语句取回全部」这个行为**没有了** —— 原来那条 adminReviewListCostsASingleQuery
    // 钉的正是它, 所以它被下面这几条取代(而不是被"修正").

    /** 灌 n 条评论, 番剧 id 各自不同 —— 番剧名那一次批量查询才有多个 id 可查 */
    private void seedReviewsOnDistinctSubjects(int n) {
        long base = java.sql.Timestamp.valueOf("2030-01-01 00:00:00").getTime();
        for (int i = 0; i < n; i++) {
            User author = userRepository.save(User.builder()
                    .username("s" + UUID.randomUUID().toString().substring(0, 12))
                    .password("x").role("USER").status("ACTIVE").build());
            jdbc.update("INSERT INTO review (user_id, subject_id, rating, content, created_at) "
                            + "VALUES (?, ?, ?, ?, ?)",
                    author.getId(), SUBJECT_BASE + i, 8, "c" + i,
                    new java.sql.Timestamp(base + i * 60_000L));
        }
    }

    /** 取一页的行(只留 content, 断言里全是它在说话) */
    @SuppressWarnings("unchecked")
    private static List<String> contentsOfPage(Map<String, Object> page) {
        return ((List<Map<String, Object>>) page.get("list")).stream()
                .map(m -> (String) m.get("content")).toList();
    }

    /** 取一页的整行, 给"键在不在、值是什么"这类断言用 */
    @SuppressWarnings("unchecked")
    private static List<Map<String, Object>> rowsOfPage(Map<String, Object> page) {
        return (List<Map<String, Object>>) page.get("list");
    }

    /**
     * 管理端评论列表一页 = <b>恰好 4 条</b>: count + 取页 + 番剧名批量 + 举报摘要批量.
     *
     * <p>后两条都是"行里要一个不在 {@code review} 表上的字段, 而两张表之间**没有外键**,
     * 只能另查一次": 番剧名给 {@code animeTitle}({@code review.subject_id} → {@code anime}),
     * 举报给 {@code reportCount}/{@code latestReason}({@code review} → {@code review_report})。
     * 逐行查会变成 2+2N, 所以把数字钉死在 4 才有意义 —— 与
     * {@link #trackingListCostsTwoQueriesRegardlessOfRowCount} 是同一条道理.
     *
     * <p><b>数字是 c94 从 3 改成 4 的, 不是顺手加的。</b> 举报摘要那条查询**不看有没有
     * 举报**: 没有举报时它收下一个空 IN、返回空列表, 但仍是一条语句。这个取舍是刻意的 ——
     * 把"有没有举报"当成一个分支去省那次查询, 等于让语句条数随数据变; 而这一页的成本
     * 要能被一个常量盯住, 才是这条用例存在的理由。(同一取舍在
     * {@code AdminService.toAdminReviewRows} 的注释里写的是另一半.)
     *
     * <p>参数化取两档页大小: 要防的回归是"有人在循环里又补一次查询", 那种改动会让
     * 次数随着行数长上去, 而不是从 4 变成 5. 只测一个页大小的话, 恰好等于某个数字
     * 也能过.
     */
    @ParameterizedTest(name = "每页 {0} 条")
    @ValueSource(ints = {5, 20})
    @DisplayName("管理端评论列表: 不论页大小都是 4 条语句(count + 取页 + 番剧名 + 举报摘要)")
    void adminReviewPageCostsFourQueriesRegardlessOfPageSize(int limit) {
        seedReviewsOnDistinctSubjects(limit + 3);

        long statements = statementsFor(
                () -> adminService.getReviewPage(null, null, null, null, null, 1, limit));

        assertThat(statements).as("每页 %d 条", limit).isEqualTo(4);
    }

    /**
     * 番剧名那一格: 本地缓存过就给名字, 没缓存过就 <b>null</b>(不编一个假名字).
     *
     * <p>上面那条只数语句条数, 分辨不出"批量查了但按错的 id 去取值" —— 那一样是 3 条.
     * 所以这里把两个分支都断出来: {@code review.subject_id} 与 {@code anime} 之间没有
     * 外键, 那部番完全可能还没被拉进本地库, 而**"这个 id 对应的番剧还没进本地库"
     * 本身就是真信息**, 前端靠它退化成「番剧 #656083」.
     *
     * <p>两条评论的 subject_id 刻意不同: 相同的话, "取到了名字"可能只是两行都撞上了
     * 同一部番, 与那一行对不对得上无关.
     */
    @Test
    @DisplayName("管理端评论列表: 缓存过的番给名字, 没缓存过的给 null(键必须在)")
    void adminReviewRowsResolveTheAnimeTitlePerRow() {
        seedReviewsOnDistinctSubjects(2);
        jdbc.update("INSERT INTO anime (id, title, tags) VALUES (?, ?, ?)",
                SUBJECT_BASE, "番0", "科幻");

        List<Map<String, Object>> rows = rowsOfPage(
                adminService.getReviewPage(null, null, null, null, null, 1, 20));

        assertThat(rows).hasSize(2);
        // 键**必须都在**(哪怕值是 null): 少了这个键, 前端那一格走的是 undefined 分支,
        // 而"没有这个键"与"这部番还没进本地库"在界面上长得一模一样
        assertThat(rows).allSatisfy(r -> assertThat(r).containsKey("animeTitle"));
        // 列表按主键倒序, 所以 c1(subject_id = BASE+1)在前
        assertThat(rows.get(0)).containsEntry("content", "c1").containsEntry("animeTitle", null);
        assertThat(rows.get(1)).containsEntry("content", "c0").containsEntry("animeTitle", "番0");
    }

    /**
     * 越界页只发 <b>1</b> 条(count), 而且 total 仍是真实的匹配数.
     *
     * <p>⚠️ 计划里这笔账记的是"空页 = 2 条", 实测是 <b>1</b> 条:
     * {@code offset >= total} 那道提前返回排在取页那条之前, 所以"取页"与"番剧名批量"
     * 两条**都不会发出去** —— 与 {@link #outOfRangeUserPageOnlyRunsTheCount} 逐字同形.
     * {@code toAdminReviewRows} 里那个空集合守卫因此不是这条用例的落点: 它在
     * "count 时 offset 还够、真取页时那一页已被别人删空"的并发窗口下才够得着,
     * 单线程测不到. 两处守卫都留着(一处省一次主键扫描, 一处是并发下的第二道),
     * 但账要记对.
     *
     * <p>total 报真实值这一条不能省: 报 0 的话前端按 {@code ceil(total/limit)} 算出来的
     * 翻页控件会凭空少几页, 用户从最后一页往回点就回不去了. 分页条是按 total 渲染的,
     * 所以这也是"越界那一页还能点回第 1 页"的前提.
     */
    @Test
    @DisplayName("管理端评论列表: 越界页只发 count 一条, total 仍是真实匹配数")
    void outOfRangeReviewPageOnlyRunsTheCount() {
        seedReviewsOnDistinctSubjects(3);
        AtomicReference<Map<String, Object>> holder = new AtomicReference<>();

        long statements = statementsFor(() -> holder.set(
                adminService.getReviewPage(null, null, null, null, null, 9999, 20)));

        assertThat((List<?>) holder.get().get("list")).isEmpty();
        assertThat(holder.get().get("total")).isEqualTo(3);
        assertThat(statements).as("取页与番剧名那两条根本不该发出去").isEqualTo(1);
    }

    /**
     * 同一个关键词同时收窄 count 与取页 —— 两边必须是**同一份** WHERE.
     *
     * <p>计数那条若忘了带关键词, 结果是"共 3000 条评论"而列表里只有几条: 前端按那个
     * 数字算出几十页, 翻过去每一页都是空的. 与
     * {@link #keywordNarrowsBothTheCountAndTheRows} 是同一条.
     */
    @Test
    @DisplayName("管理端评论列表: 关键词与评分档位同时作用于 total 与列表")
    void adminReviewFiltersNarrowBothTheCountAndTheRows() {
        seedReviewsForFiltering();

        Map<String, Object> low = adminService.getReviewPage(null, "low", null, null, null, 1, 20);
        Map<String, Object> keyword = adminService.getReviewPage("t1", null, null, null, null, 1, 20);
        Map<String, Object> both = adminService.getReviewPage("t1", "high", null, null, null, 1, 20);

        assertThat(low.get("total")).as("差评 1–4 只该收下前两条").isEqualTo(2);
        assertThat(contentsOfPage(low)).containsExactly("t1", "t0");
        assertThat(keyword.get("total")).isEqualTo(1);
        assertThat(contentsOfPage(keyword)).containsExactly("t1");
        // 两个条件必须**同时**生效(AND 而不是 OR): 用 OR 的话这条会是 3
        assertThat(both.get("total")).as("t1 是中评, 落在好评档里就该是 0").isEqualTo(0);
        assertThat(contentsOfPage(both)).isEmpty();
    }

    /**
     * 「只看被举报的」同时收窄 total 与列表, 而且**只认待处理的**。
     *
     * <p>种子里 {@code w3} 只被忽略过 —— 它是这条用例的落点: 判据写成"这张评论有没有
     * 举报行"的实现, 会把 {@code w3} 一起留下(total 变 3), 而"有没有**待处理**的举报"
     * 才该是判据。这个区别在别的用例上一点都不显形: {@code w2} 同时有一条待处理和一条
     * 已忽略, 两种实现都留下它。所以**必须有一个只被忽略过的种子行**, 否则这条用例
     * 看着在测筛选, 实际两种实现都能过。
     */
    @Test
    @DisplayName("管理端评论列表: 只看被举报时 total 与列表一起收窄, 只被忽略过的不算")
    void adminReviewReportedFilterNarrowsBothTheCountAndTheRows() {
        seedReviewsForReportedFilter();

        Map<String, Object> all = adminService.getReviewPage(null, null, null, null, null, 1, 20);
        Map<String, Object> reported =
                adminService.getReviewPage(null, null, "true", null, null, 1, 20);

        assertThat(all.get("total")).as("不带这个开关时四条都在").isEqualTo(4);
        assertThat(reported.get("total"))
                .as("w0 没人举报, w1/w2 有待处理的, w3 只被忽略过 —— 该是 2")
                .isEqualTo(2);
        assertThat(contentsOfPage(reported)).containsExactly("w2", "w1");
    }

    /**
     * 这一句必须是 {@code EXISTS} 半连接, 不能退化成 {@code JOIN review_report} + {@code GROUP BY}。
     *
     * <p>两种写法**返回的行完全一样**, 所以上面那条行序断言一条都拦不住 —— 它们只在
     * 一件事上不同: {@code GROUP BY} 之后行数不再与评论一一对应, Hibernate 就没法把
     * {@code LIMIT} 交给数据库, 于是取页那句退化成"整张评论表读进 JVM 再切页", 而那正是
     * 这一轮要消灭的毛病本身。这里能证明的只有 SQL 文本这一条路。
     *
     * <p>{@code fetch first} 是 H2 对 {@code setMaxResults} 的渲染, 与
     * {@link #adminReviewPageSqlNamesTheRequestedSortColumn} 里那半句同一个来历。
     */
    @Test
    @DisplayName("管理端评论列表的 SQL: 只看被举报是 EXISTS 半连接, 分页仍然下推")
    void adminReviewReportedFilterSqlStaysAHalfJoin() {
        seedReviewsForReportedFilter();

        adminService.getReviewPage(null, null, "true", null, null, 1, 20);
        String sql = pageSql();

        assertThat(sql).as("每行至多贡献一行, limit 才交得出去")
                .containsIgnoringCase("exists")
                .doesNotContainIgnoringCase("group by");
        assertThat(sql).as("子查询要落在举报表上, 而不是另找一张表").containsIgnoringCase("review_report");
        assertThat(sql).as("分页必须是库做的, 不是读回来再切").containsIgnoringCase("fetch first");
    }

    /**
     * 四条评论: {@code w0} 没人举报, {@code w1} 一条待处理, {@code w2} 一条待处理 +
     * 一条已忽略, <b>{@code w3} 只有一条已忽略</b>。
     *
     * <p>{@code w3} 是刻意造的, 理由写在上面那条用例上。{@code w2} 挂着两条举报则要求
     * <b>两个不同的举报人</b> —— {@code (review_id, reporter_id)} 上有唯一约束。
     */
    private void seedReviewsForReportedFilter() {
        long base = java.sql.Timestamp.valueOf("2030-01-01 00:00:00").getTime();
        for (int i = 0; i < 4; i++) {
            // 一行一个作者: review 上有 uk_review_user_subject(一人对一部番只留一条评论),
            // 四条评论挂同一个 subject 就必须换人 —— 与 {@link #seedReviews} 同一条理由
            jdbc.update("INSERT INTO review (user_id, subject_id, rating, content, created_at) "
                            + "VALUES (?, ?, 8, ?, ?)",
                    freshUserId("w"), SUBJECT_BASE, "w" + i,
                    new java.sql.Timestamp(base + i * 60_000L));
        }
        // 举报人另建两个, 不复用那四个作者 —— 走 JDBC 绕得过「不能举报自己的评论」
        // 那条规则, 但种子数据长得像真的才不至于误导下一个人
        long first = freshUserId("s");
        long second = freshUserId("s");

        seedReportOn("w1", first, "PENDING");
        seedReportOn("w2", first, "PENDING");
        seedReportOn("w2", second, "DISMISSED");
        seedReportOn("w3", first, "DISMISSED");
    }

    /**
     * 存一个新用户并给出它的 id. 前缀由调用方给, 用来把这一组的用户名与别组错开
     * (别处是 {@code q}/{@code r}/{@code k}/{@code f}/{@code p})。
     *
     * <p>本类里所有会碰到 {@code review} 的种子数据都得一人一行: 那张表上有
     * {@code uk_review_user_subject}(一人对一部番只留一条评论), 而同组的评论又都挂在
     * 同一个 {@code SUBJECT_BASE} 上。
     */
    private long freshUserId(String prefix) {
        return userRepository.save(User.builder()
                .username(prefix + UUID.randomUUID().toString().substring(0, 12))
                .password("x").role("USER").status("ACTIVE").build()).getId();
    }

    /** 走 JDBC: 这里要的是"库里已经有这样的举报行", 举报怎么**写**进去由别的类管 */
    private void seedReportOn(String content, long reporterId, String status) {
        Long reviewId = jdbc.queryForObject(
                "SELECT id FROM review WHERE content = ?", Long.class, content);
        assertThat(reviewId).as("种子行 <%s> 应当刚灌进去", content).isNotNull();
        jdbc.update("INSERT INTO review_report (review_id, reporter_id, reason, status, created_at)"
                        + " VALUES (?, ?, 'SPAM', ?, ?)",
                reviewId, reporterId, status,
                java.sql.Timestamp.valueOf("2030-01-01 00:00:00"));
    }

    /**
     * 正文是 {@code t0..t3}, 评分前两条是 3、后两条是 9.
     *
     * <p>正文刻意用字母 <b>t</b>: {@code seedReviews} 用的 {@code c0/c1} 里那个
     * {@code c} <b>是十六进制字符</b>, 而本类灌的用户名都是 UUID 的十六进制片段 ——
     * 关键词 {@code "c1"} 有可能在某个随机用户名里出现, 于是"命中 1 条"变成"命中 2 条",
     * 而且只在运气不好的时候红. {@code t} 不在 {@code [0-9a-f]} 里, 择得干净.
     */
    private void seedReviewsForFiltering() {
        long base = java.sql.Timestamp.valueOf("2030-01-01 00:00:00").getTime();
        int[] ratings = {3, 3, 9, 9};
        for (int i = 0; i < ratings.length; i++) {
            User author = userRepository.save(User.builder()
                    .username("f" + UUID.randomUUID().toString().substring(0, 12))
                    .password("x").role("USER").status("ACTIVE").build());
            jdbc.update("INSERT INTO review (user_id, subject_id, rating, content, created_at) "
                            + "VALUES (?, ?, ?, ?, ?)",
                    author.getId(), SUBJECT_BASE, ratings[i], "t" + i,
                    new java.sql.Timestamp(base + i * 60_000L));
        }
    }

    /**
     * 六种排序: 各自给出**自己的那一页**, 而且三页拼起来一条不漏、一条不重.
     *
     * <p>语句条数分辨不出「切在数据库上」与「整表读回来再在内存里切」—— 都是 3 条.
     * 只有"拿回来的是哪几条"能, 所以这里把每条排序的前两行都点名断言.
     *
     * <p>灌进去的赞数与回复数是**刻意打乱**的两组: 见 {@link #seedReviewsForSorting} 里
     * 那张表. 三个键若彼此同序, 六种组合里会有一半给出同一个答案, 于是"排序真的换了"
     * 就断言不出来 —— 按下面那张表, 六种组合的首页两行<b>两两不同</b>.
     */
    @ParameterizedTest(name = "{0} {1}")
    @CsvSource({
            // sort,    order, 首页两行,  第二页两行,  第三页两行
            "id,       desc,  c5|c4,     c3|c2,      c1|c0",
            "id,       asc,   c0|c1,     c2|c3,      c4|c5",
            "likes,    desc,  c2|c4,     c0|c5,      c3|c1",
            "likes,    asc,   c1|c3,     c5|c0,      c4|c2",
            "replies,  desc,  c2|c0,     c4|c1,      c5|c3",
            "replies,  asc,   c3|c5,     c1|c4,      c0|c2",
    })
    @DisplayName("管理端评论列表: 六种排序各自切在库上, 三页拼起来不重不漏")
    void adminReviewSortsAreCutInTheDatabase(String sort, String order,
                                             String first, String second, String third) {
        seedReviewsForSorting();

        List<String> page1 = contentsOfPage(
                adminService.getReviewPage(null, null, null, sort, order, 1, 2));
        List<String> page2 = contentsOfPage(
                adminService.getReviewPage(null, null, null, sort, order, 2, 2));
        List<String> page3 = contentsOfPage(
                adminService.getReviewPage(null, null, null, sort, order, 3, 2));

        assertThat(page1).as("%s %s 的第 1 页", sort, order).isEqualTo(split(first));
        assertThat(page2).as("%s %s 的第 2 页", sort, order).isEqualTo(split(second));
        assertThat(page3).as("%s %s 的第 3 页", sort, order).isEqualTo(split(third));
        // 三页拼起来必须正好是那六行. 少了这个, 上面三条各自看都对, 而"第 2 页重复
        // 了第 1 页的一行、另有一行永远看不到"照样能过 —— 那正是"在内存里切"与
        // "在库里切"最容易被混淆的失效方式
        assertThat(Stream.concat(page1.stream(), Stream.concat(page2.stream(), page3.stream())))
                .hasSize(6).doesNotHaveDuplicates();
    }

    /**
     * 生成的 SQL: 排序键是请求的那一列, 行数限制在库里做.
     *
     * <p>为什么必须看 SQL 文本: H2 上"排序对不对"能被上面的行序断言抓到, 但
     * "切片在库上还是在内存里"抓不到 —— 两种写法返回的行一模一样. 只有
     * {@code fetch first}(H2 对 {@code setMaxResults} 的渲染)能证明是库在切.
     *
     * <p>回复数那一列尤其要盯着: {@code ORDER BY like_count DESC} 写成
     * {@code like_count} 而漏了 {@code reply_count} 不会报错, 只会让"按回复数排"
     * 变成"按赞数排", 而且两者恰好都非空、都能排.
     *
     * <p><b>取语句用的是 {@code lastContaining("order by")} 而不是 {@code last()}</b>:
     * 这一页非空, 取完之后还会为番剧名发一条批量查询, 于是 {@code last()} 拿到的是那条
     * (理由写在 {@code SqlRecorder.lastContaining} 上)。这也是本用例第一条吃掉的红:
     * 断言曾经打在一条 {@code from anime} 上.
     *
     * <p><b>⚠️ 不要断言 {@code "reply_count asc"} 这种带方向词的整串 —— 实测渲染出来是
     * {@code order by r1_0.reply_count,r1_0.id desc}: Hibernate 把正序的 {@code asc}
     * 省略了(它是默认方向), 只留了 {@code desc}。</b>于是那条断言测的不是"排序对不对",
     * 而是"Hibernate 有没有把默认方向词写出来", 而且它红了之后最像"修好了"的改法是把
     * 期望值改成 {@code reply_count desc} —— 一条永远为假的断言, 正序倒序它都觉得对.
     *
     * <p>所以这里拆成两问: 列对不对(有没有写错成 {@code like_count}), 方向对不对
     * (正序时不出现 {@code reply_count desc}). 方向那半边其实是弱守卫 —— 真正管用的是
     * 上面 {@code adminReviewSortsAreCutInTheDatabase} 的行序断言(它证明了正序真的升),
     * 这里只是补一条"SQL 里确实是那一列".
     */
    @Test
    @DisplayName("管理端评论列表的 SQL: 排序键是请求的那一列, 且带行数限制")
    void adminReviewPageSqlNamesTheRequestedSortColumn() {
        seedReviewsForSorting();

        adminService.getReviewPage(null, null, null, "replies", "asc", 1, 2);
        String sql = pageSql();
        String orderBy = orderByClause(sql);

        assertThat(orderBy).as("按回复数排. 写成 like_count 不会报错, 只会静默变成按赞数排")
                .containsIgnoringCase("reply_count")
                .doesNotContainIgnoringCase("like_count");
        assertThat(orderBy).as("正序. Hibernate 省略冗余的 asc, 所以只能反过来断言")
                .doesNotContainIgnoringCase("reply_count desc");
        assertThat(orderBy).as("第二键是主键, 同值行才有一个稳定的序 —— 少了它翻页会重复/丢行")
                .containsIgnoringCase("id desc");
        assertThat(sql).as("分页必须是库做的, 不是读回来再切")
                .containsIgnoringCase("fetch first");

        adminService.getReviewPage(null, null, null, "likes", "desc", 1, 2);
        assertThat(orderByClause(pageSql()))
                .as("倒序时 desc 在位, 而且没有串到回复数那一列去")
                .containsIgnoringCase("like_count desc")
                .doesNotContainIgnoringCase("reply_count");
    }

    /** SQL 里 {@code order by} 后面的那一段; 没有就回空串(断言会红, 而不是 NPE) */
    private static String orderByClause(String sql) {
        int at = sql.toLowerCase(Locale.ROOT).lastIndexOf(" order by ");
        return at < 0 ? "" : sql.substring(at + " order by ".length());
    }

    /** {@code "c5|c4"} → {@code ["c5", "c4"]} */
    private static List<String> split(String csv) {
        return List.of(csv.split("\\|"));
    }

    /**
     * 灌 6 条评论, 让**主键 / 赞数 / 回复数**三者给出三个不同的序.
     *
     * <p>三个键同序的话, 六种排序组合里有一半会返回同一个答案, 于是那些用例变成
     * 同一条用例(改错排序键也照样绿). 下面两张表是手排的, 目的只有一个:
     * 六种组合各自的**前两行两两不同**.
     *
     * <pre>
     * 行   like_count   reply_count
     * c0      3            4
     * c1      0            2
     * c2      5            5
     * c3      1            0
     * c4      4            3
     * c5      2            1
     * </pre>
     *
     * <p>{@code created_at} 仍给递增的时刻(部分索引与"缺时间的行"无关, 但别的用例
     * 共用同一张表, 留一个确定的形状省得互相干扰).
     */
    private void seedReviewsForSorting() {
        int[] likes = {3, 0, 5, 1, 4, 2};
        int[] replies = {4, 2, 5, 0, 3, 1};
        long base = java.sql.Timestamp.valueOf("2030-01-01 00:00:00").getTime();
        for (int i = 0; i < likes.length; i++) {
            User author = userRepository.save(User.builder()
                    .username("k" + UUID.randomUUID().toString().substring(0, 12))
                    .password("x").role("USER").status("ACTIVE").build());
            jdbc.update("INSERT INTO review (user_id, subject_id, rating, content, created_at,"
                            + " like_count, reply_count) VALUES (?, ?, ?, ?, ?, ?, ?)",
                    author.getId(), SUBJECT_BASE, 8, "c" + i,
                    new java.sql.Timestamp(base + i * 60_000L), (long) likes[i], (long) replies[i]);
        }
    }

    // ========== 回复列表 ==========
    //
    // 这一组的预算比评论列表**多一条**, 而且是有意的: getReplies 先问一句
    // 「这条评论还在吗」, 不在就 404。没有这一步, 「评论已经被删掉」与「这条评论
    // 还没人回复」在响应里长得一模一样(都是一个空列表) —— 前端手里那条评论是上一秒
    // 从列表里拿到的, 它会一直显示一个空回复区, 不报错、也不消失。
    // 代价就是每展开一次多一次主键查询, 所以下面的数字是 2 与 3, 不是 1 与 2。

    /** 一条短评 + 挂在它下面的 n 条回复(每条来自一个不同的人), 返回短评 id */
    private long seedReplies(int n) {
        seedReviews(1);
        long reviewId = jdbc.queryForObject(
                "SELECT id FROM review WHERE subject_id = ?", Long.class, SUBJECT_BASE);
        long base = java.sql.Timestamp.valueOf("2030-01-01 00:00:00").getTime();
        for (int i = 0; i < n; i++) {
            User author = userRepository.save(User.builder()
                    .username("p" + UUID.randomUUID().toString().substring(0, 12))
                    .password("x")
                    .role("USER")
                    .status("ACTIVE")
                    .build());
            jdbc.update("INSERT INTO review_reply (review_id, user_id, content, created_at) "
                            + "VALUES (?, ?, ?, ?)",
                    reviewId, author.getId(), "r" + i,
                    new java.sql.Timestamp(base + i * 60_000L));
        }
        return reviewId;
    }

    /**
     * 未登录看回复: **2 条** —— 存在性检查 + 一条 JOIN FETCH 的列表, 与回复条数无关。
     *
     * <p>把 1 条与 5 条摆在一起断言, 理由与追番列表那条一样: 要防的回归是"有人在循环里
     * 又补一次查询", 那种改动会让次数变成 2+N, 而不是 3。只测一个数据量的话, 恰好
     * 等于某个数字也能过。
     */
    @ParameterizedTest(name = "{0} 条回复")
    @ValueSource(ints = {1, 5})
    @DisplayName("回复列表: 匿名 2 次查询(存在性检查 + 列表), 与条数无关")
    void replyListCostsTwoQueriesForAGuest(int n) {
        long reviewId = seedReplies(n);

        long statements = statementsFor(() -> reviewReplyService.getReplies(0L, reviewId));

        assertThat(statements).as("回复 %d 条", n).isEqualTo(2);
    }

    /**
     * 登录之后多出来的**恰好一条**: 批量问"这一串回复里我赞过哪些"。
     *
     * <p>数字钉死在 3 而不是"至少 3": 逐条查会是 2+N, 而这是最容易被下一个人
     * "顺手改成更好懂的写法"的地方 —— 返回值一个字节都不差, 只有次数看得出来。
     */
    @Test
    @DisplayName("回复列表: 登录态 3 次(存在性 + 列表 + 批量查我赞过哪些), 不是每条一次")
    void replyListCostsThreeQueriesForALoggedInUser() {
        long reviewId = seedReplies(10);

        long statements = statementsFor(() -> reviewReplyService.getReplies(user.getId(), reviewId));

        assertThat(statements).describedAs("存在性 1 + 列表 1 + likedByMe 批量 1").isEqualTo(3);
    }

    /**
     * 一条回复都没有时, 登录态**也只有 2 条** —— 那个空的 id 集合不会进 JPQL 的 {@code IN}。
     *
     * <p>与 {@link #reviewListOfASubjectWithNoReviewsIsStillOneQuery} 守的是同一处边界
     * (空集合在 JPQL 里没有合法写法, Hibernate 6 恰好把它渲染成 {@code 1=0}, 但那是它的
     * 实现选择)。守卫的价值是那条语句根本不发, 与它长什么样无关。
     */
    @Test
    @DisplayName("一条回复都没有: 登录态下也只有 2 次, 空集合不会进 IN")
    void replyListOfAReviewWithNoRepliesIsStillTwoQueries() {
        long reviewId = seedReplies(0);

        long statements = statementsFor(() -> reviewReplyService.getReplies(user.getId(), reviewId));

        assertThat(statements).isEqualTo(2);
    }

    /**
     * 回复按时间**正序**(先发生的在前)—— 与评论列表的倒序是两套读法, 刻意不同。
     *
     * <p>评论列表倒序是因为"最新的那条最值得先看"; 而回复合起来读是一段对话,
     * 倒序会让人从下往上读。两个序不能靠"碰巧"区分开, 所以数据是正着灌的,
     * 一旦有人把某一边的 ORDER BY 抄到另一边, 这条就会红。
     */
    @Test
    @DisplayName("回复列表: 按时间正序, 是与评论列表相反的读法")
    void replyListIsOldestFirst() {
        long reviewId = seedReplies(5);

        List<String> contents = reviewReplyService.getReplies(0L, reviewId).stream()
                .map(m -> (String) m.get("content"))
                .toList();

        assertThat(contents).containsExactly("r0", "r1", "r2", "r3", "r4");
    }

    /**
     * 回复列表的 SQL 里, {@code created_at} 的 NULL 是显式分组的, 顺序不随库变。
     *
     * <p>与 {@link #hotOrderSqlIsPushedDownWithNullSafeOrdering} 同一条理由, 而且同样是
     * <b>只有 SQL 文本守得住</b>: {@code created_at} 可空, 而 {@code ORDER BY x ASC} 时
     * NULL 排哪, H2 与 PostgreSQL 正好相反 —— 但本地只有 H2, 语义用例在两个库上都会是绿的。
     * 这条约束眼下没有分页在它后面(一条评论下的回复封顶 200 条, 不走 OFFSET), 所以失守的
     * 后果不是漏行, 而是<b>同一份数据在开发档与线上读出来的楼不一样</b> —— 依然是个
     * 不报错、只在换库那天现形的 bug。
     */
    @Test
    @DisplayName("回复列表的 SQL: 缺时间的行显式分组, 顺序不随库变")
    void replyListSqlIsDialectIndependentAboutNulls() {
        long reviewId = seedReplies(3);

        reviewReplyService.getReplies(0L, reviewId);
        String sql = lastSqlNormalized();

        assertThat(sql).containsIgnoringCase("order by")
                .as("缺时间的行要显式分组, 否则 ASC 下 NULL 排哪随库变")
                .containsIgnoringCase("case when")
                .as("第二键要换成常量, 让 'ORDER BY 里没有 NULL' 字面成立")
                .containsIgnoringCase("coalesce");
    }

    /**
     * 评论不存在时: 抛 404, 而且**只发一条语句** —— 存在性检查短路了后面那条列表查询。
     *
     * <p>这条钉的是那个检查的**位置**: 它必须在最前面。往后挪一位(先查列表、再判断)
     * 结果一样是 404, 但每次访问一条已被删掉的评论都会白发一条 JOIN FETCH。
     */
    @Test
    @DisplayName("评论不存在: 404 且只发一条语句(检查在列表之前)")
    void repliesOfAMissingReviewCostOneQuery() {
        long missingReview = SUBJECT_BASE + 999_999L;
        Statistics stats = statsCleared();

        assertThatThrownBy(() -> reviewReplyService.getReplies(0L, missingReview))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("评论不存在");

        assertThat(stats.getPrepareStatementCount()).isEqualTo(1);
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
     * 管理端取页那一句 —— 按内容挑, 不是按"最后一条".
     *
     * <p>原因见 {@code SqlRecorder.lastContaining}: 取页之后还有两条补数据的批量查询
     * (番剧名、举报摘要), 而它们才是那次调用里最后发出的语句.
     *
     * <p><b>⚠️ 这里的针从 {@code "order by"} 换成了 {@code "from review "}(c94).</b>
     * 原先那两个参数之所以够用, 是因为取页那句**恰好**是那次调用里最后一条带
     * {@code order by} 的语句; 举报摘要那条也带 {@code order by rr.id DESC}, 于是
     * {@code lastContaining("order by")} 会**静默地**换成它 —— 断言照跑, 只是从此
     * 打在另一条 SQL 上. 这与本用例自身第一版吃掉的红是同一类(那次打在
     * {@code from anime} 上), 所以针要钉在"哪张表"上, 而不是钉在"有没有 order by"上.
     *
     * <p>{@code "from review "} 末尾那个空格不是手滑: 少了它, 同一个前缀会连
     * {@code from review_report} 与 {@code from review_reply} 一起匹配上, 而那正是
     * 这次要排除的两条.
     */
    private static String pageSql() {
        // 没匹配上就回空串(断言会红), 不是 NPE —— 与下面 orderByClause 同一条理由
        String found = SqlRecorder.lastContaining("from review ");
        return found == null ? "" : found.replaceAll("\\s+", " ");
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
     * 分类浏览页那条四组语句的形状: <b>四条</b> {@code IN} 半连接, 且<b>每条各带一个</b>
     * 短路开关, 没有 {@code EXISTS}、没有顶层 {@code join anime_tag}.
     *
     * <p>判据沿用 {@link #tagBrowseSqlUsesInSubquery} 那一套(那条写法在这个库上是全表
     * 扫描 + 逐行主键回探, 满库时慢 138 倍, 而两种写法返回的行一模一样, 只有 SQL 文本
     * 分得出来), 多出来的是这一条独有的两件事:
     *
     * <p><b>为什么数"四条".</b> 少一条就是"某一组被静默忽略" —— 用户勾了地区却拿到
     * 全站, 返回值照样 200, 页面上看不出任何异常.
     *
     * <p><b>为什么数"四个 false".</b> 每一条子句前面挂着一个 {@code :xxxActive = FALSE OR},
     * 未选中的组靠它短路成恒真. 少一个, 那一组就退化成"恒不匹配"; 而如果有人图省事
     * 把整个四组表达式用外层一个 {@code :activeCount = 0 OR} 包起来, 那条短路只对
     * "一组都没选"生效、对"选了一部分"毫无作用 —— 那正是最常见的用法. 逐条数出现次数,
     * 就是让那种"看着等价"的改写在这里变红.
     *
     * <p>断言在**去掉全部空白、转小写**的 SQL 上做: 运算符两侧有没有空格由 Hibernate
     * 的渲染器决定, 那是它的实现细节, 不该让这条用例跟着它一起变.
     */
    @Test
    @DisplayName("四组标签的 SQL: 四条 IN 半连接各带一个短路开关, 没有 EXISTS")
    void tagGroupSqlUsesFourGuardedInSubqueries() {
        seedSeasonedAnime(1, "9q63-07");
        long genre = tagIdOf("qct-group-genre");
        long region = tagIdOf("qct-group-region");

        animeRepository.findFilteredByTagGroupsDate(null, null, null,
                true, List.of(genre),
                false, List.of(-1L),
                false, List.of(-1L),
                true, List.of(region),
                PageRequest.of(0, 5));
        String sql = squash(lastSqlNormalized());

        assertThat(occurrences(sql, "idin(select")).as("四条子查询, 一组一条")
                .isEqualTo(4);
        assertThat(occurrences(sql, "false")).as("每条子句一个短路开关, 不能合并成一个")
                .isEqualTo(4);
        assertThat(sql).as("EXISTS 那条写法是 124 秒")
                .doesNotContain("exists")
                .as("顶层 join 关联表是当初被换掉的那种写法")
                .doesNotContain("joinanime_tag");
    }

    /** 去掉全部空白并转小写 —— 拿它来做与渲染空格无关的形状断言 */
    private static String squash(String sql) {
        return sql.replaceAll("\\s+", "").toLowerCase(Locale.ROOT);
    }

    private static int occurrences(String haystack, String needle) {
        int count = 0;
        for (int at = haystack.indexOf(needle); at >= 0; at = haystack.indexOf(needle, at + 1)) {
            count++;
        }
        return count;
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

    // ========== 管理端用户列表 ==========
    //
    // 用户表这一条路以前根本没有分页: 接口一次把**全部**用户吐给前端, 而且这条
    // 路径上没有任何断言盯着"发了几条语句". 下面几条盯的是同一件事的三面:
    // 语句条数与页大小无关、切片是库做的、以及越界页只该发一条.

    /**
     * 灌 n 个用户.
     *
     * <p>用户名带随机后缀: {@code username} 上有唯一索引, 而别的用例也在往这张表里灌.
     * 前缀固定成 {@code pg} 是为了后面能用关键词把它从表里其它用户中**择出来** ——
     * 别的用例用的都是十六进制 UUID 片段(不含 p / g), 所以 {@code keyword=pg}
     * 命中的恰好是本方法灌的这几行.
     *
     * <p>{@code created_at} 显式给成递增的时刻: 默认排序按它倒序, 全 NULL 的话
     * "第几页是哪几条"就成了运气, 而下面要断言的正是切页切对了没有.
     */
    private void seedUsersForPaging(int n) {
        // 先清掉上一个用例灌的那些. 这一类是共享一个 Spring 上下文(因而共享一个库)的,
        // 而**每个**用例都靠 `pg` 前缀把自己那几行择出来 —— 不清的话, 前面那个参数化
        // 用例灌的 23 行还在, 于是"共 5 个用户"变成"共 30 个", 断言红得毫无道理.
        // 这几行没有任何外键指向它们(追番/评论都是由别的用例的用户建的), 删得掉.
        jdbc.execute("DELETE FROM \"user\" WHERE username LIKE 'pg%'");
        long base = java.sql.Timestamp.valueOf("2030-01-01 00:00:00").getTime();
        List<Object[]> args = new ArrayList<>();
        for (int i = 0; i < n; i++) {
            args.add(new Object[]{
                    "pg" + UUID.randomUUID().toString().substring(0, 10),
                    "x", "USER", "ACTIVE", new java.sql.Timestamp(base + i * 60_000L)});
        }
        jdbc.batchUpdate("INSERT INTO \"user\" (username, password, role, status, created_at) "
                + "VALUES (?, ?, ?, ?, ?)", args);
    }

    /** 用户表总行数 —— 这张表在本类里**没有**被清过, 所以总数必须现数, 不能写死 */
    private int userRowCount() {
        return jdbc.queryForObject("SELECT COUNT(*) FROM \"user\"", Integer.class);
    }

    @SuppressWarnings("unchecked")
    private static List<Long> userIdsOf(Map<String, Object> page) {
        return ((List<Map<String, Object>>) page.get("list")).stream()
                .map(m -> ((Number) m.get("id")).longValue()).toList();
    }

    @ParameterizedTest(name = "每页 {0} 条")
    @ValueSource(ints = {5, 20})
    @DisplayName("用户分页: 不论页大小都是 2 条语句(一条 count, 一条取页)")
    void userPageCostsTwoQueriesRegardlessOfPageSize(int limit) {
        seedUsersForPaging(limit + 3);

        long statements = statementsFor(
                () -> adminService.getUserPage(null, null, null, null, null, 1, limit));

        // 多出来说明有谁在循环里补了查询(比如给每一行单独查一次锁定状态);
        // 少一条则是 total 拿这一页的行数糊出来的 —— 那样第 2 页之后就没有了,
        // 而接口照样 200
        assertThat(statements).as("每页 %d 条", limit).isEqualTo(2);
    }

    /**
     * 第 2 页与第 1 页不重叠, 且真的按注册时间倒序.
     *
     * <p>语句条数分辨不出「切在数据库上」与「全读回来再在内存里切」(都是 2 条),
     * 只有"拿回来的是哪几条"能. 顺带钉住 {@code PageRequest.of(page - 1, ...)} 那个
     * {@code -1}: 少了它第 1 页会从第 limit+1 行开始, 于是两页之间正好漏掉第一页,
     * 而两页各自看都"有数据".
     */
    @Test
    @DisplayName("用户分页: 第 2 页与第 1 页不重叠, 两页拼起来一条不漏")
    void userPagesDoNotOverlap() {
        seedUsersForPaging(5); // created_at 递增, 所以倒序时最后灌的排最前

        List<Long> first = userIdsOf(
                adminService.getUserPage("pg", null, null, null, null, 1, 3));
        List<Long> second = userIdsOf(
                adminService.getUserPage("pg", null, null, null, null, 2, 3));

        assertThat(first).hasSize(3);
        assertThat(second).hasSize(2);
        assertThat(first).doesNotContainAnyElementsOf(second);
        assertThat(first).isSortedAccordingTo(Comparator.reverseOrder());
        assertThat(Stream.concat(first.stream(), second.stream()))
                .hasSize(5).doesNotHaveDuplicates();
    }

    /**
     * 生成的 SQL: 排序里显式处理 NULL, 切片在库里做.
     *
     * <p><b>为什么必须看 SQL 文本.</b> {@code ORDER BY CASE WHEN created_at IS NULL …}
     * 在 H2 上<b>测不出来</b> —— H2 本来就把 NULL 排最后, 换成裸的
     * {@code ORDER BY created_at DESC} 结果一模一样, 上面那条顺序断言照样绿.
     * 真正有差别的是 PostgreSQL(DESC 下它把 NULL 当最大值排最前), 而线上 PG 不在
     * 本轮的验证范围内. 所以这条形状断言是那个坑**唯一**的哨兵, 与
     * {@link #untaggedFilterSqlIsPushedDown} 是同一条理由.
     */
    @Test
    @DisplayName("用户分页的 SQL: CASE 分组 + COALESCE, 且行数限制在库里")
    void userPageSqlIsPushedDownWithNullSafeOrdering() {
        seedUsersForPaging(3);

        adminService.getUserPage(null, null, null, null, null, 1, 2);
        String sql = lastSqlNormalized();

        assertThat(sql).containsIgnoringCase("case when")
                .as("缺时间的行要显式分组, 否则 NULL 排哪随库变, 第 2 页就会混进第 1 页的行")
                .containsIgnoringCase("coalesce")
                .as("分页必须是库做的, 不是读回来再切")
                .containsIgnoringCase("fetch first")
                .as("记下来的该是取页那条, 不是先跑的 count")
                .containsIgnoringCase("order by");
    }

    /**
     * 「最近登录」那一列的 SQL: ORDER BY 里显式处理 NULL.
     *
     * <p><b>为什么这一条非有不可, 而上面那两条语义断言不算数.</b> {@code last_login_at}
     * 是 V15 新加的列, 存量用户**全是 NULL**, 而 H2 在 DESC 下本来就把 NULL 排最后 ——
     * 也就是说把 {@code ORDER BY CASE WHEN u.lastLoginAt IS NULL …} 整段抹掉、只留
     * {@code ORDER BY COALESCE(u.lastLoginAt, :epoch) DESC}, 在 H2 上**一条语义用例都不会红**
     * (本仓在 {@code ReviewQueries} 的热度序上实测过同一件事, 见那里的长注释).
     * 真正有差别的是 PostgreSQL, 而线上 PG 不在本机验证射程内. 所以这个坑唯一的哨兵
     * 就是 SQL 文本.
     *
     * <p>断言切在 {@code order by} **之后**那一段, 而不是整条 SQL 里找 {@code last_login_at}:
     * 后者在 SELECT 列表里本来就有(这一列被投影出来了), 于是"排序里带不带它"根本验不出来 ——
     * 一条恒真的断言比没有断言更糟.
     */
    @Test
    @DisplayName("最近登录序的 SQL: ORDER BY 里 CASE 分组 + COALESCE, 缺值的行不会插到前面")
    void lastLoginOrderSqlIsPushedDownWithNullSafeOrdering() {
        seedUsersForPaging(3);

        adminService.getUserPage(null, null, null, "lastLoginAt", null, 1, 2);
        String sql = lastSqlNormalized();
        String orderBy = sql.substring(sql.toLowerCase(Locale.ROOT).lastIndexOf("order by"));

        assertThat(orderBy)
                .as("缺值的行要显式分组, 否则 NULL 排哪随库变(H2 排最后、PG 在 DESC 下排最前), "
                        + "第 2 页就会混进第 1 页的行")
                .containsIgnoringCase("case when")
                .as("第二键要换成常量, 让 'ORDER BY 里没有 NULL' 字面成立")
                .containsIgnoringCase("coalesce")
                .containsIgnoringCase("last_login_at");
    }

    /**
     * 越界页只发 count 一条, 但报出来的 total 仍然是真实的用户总数.
     *
     * <p>两条路都很容易写错而看不出来: 把 total 报成 0 的话, 前端按
     * {@code ceil(total/limit)} 算出来的翻页控件会凭空少几页, 用户从最后一页往回点
     * 就回不去了; 而少了那道守卫, 一个 {@code page=9999} 会真的带着巨大的 OFFSET
     * 发给数据库.
     */
    @Test
    @DisplayName("用户分页: 越界页只发 count 一条, total 仍是全表用户数")
    void outOfRangeUserPageOnlyRunsTheCount() {
        seedUsersForPaging(3);
        int all = userRowCount();
        AtomicReference<Map<String, Object>> holder = new AtomicReference<>();

        long statements = statementsFor(() -> holder.set(
                adminService.getUserPage(null, null, null, null, null, 9999, 20)));

        assertThat((List<?>) holder.get().get("list")).isEmpty();
        assertThat(holder.get().get("total")).isEqualTo(all);
        assertThat(statements).as("取页那一条根本不该发出去").isEqualTo(1);
    }

    /**
     * 计数与取页用的是**同一份** WHERE —— 关键词给上时 total 必须跟着变.
     *
     * <p>计数那条若忘了带关键词条件, 结果是"共 N 个用户"而列表里只有几个: N 是
     * 全表行数, 前端于是算出几十页, 翻过去每一页都是空的.
     */
    @Test
    @DisplayName("用户分页: 关键词同时作用于 total 与列表")
    void keywordNarrowsBothTheCountAndTheRows() {
        seedUsersForPaging(4);

        Map<String, Object> matched =
                adminService.getUserPage("pg", null, null, null, null, 1, 20);
        Map<String, Object> half =
                adminService.getUserPage("pg", null, null, null, null, 1, 2);

        assertThat(matched.get("total")).isEqualTo(4);
        assertThat((List<?>) matched.get("list")).hasSize(4);
        // 翻页不该改变 total —— 它答的是"匹配多少条", 不是"这一页有几行"
        assertThat(half.get("total")).isEqualTo(4);
        assertThat((List<?>) half.get("list")).hasSize(2);
    }

    /**
     * 助手侧的两个管理员工具看的是**全量**用户, 不是分页后的前 30 个.
     *
     * <p><b>为什么这条要单独钉.</b> {@code getUserList()} 与 {@code getUserPage(..)}
     * 并存, 唯一的理由是前者的调用方({@code AdminTools} 的 {@code list_users} 与
     * {@code weekly_ops_report})要的是全量语义. 看着很像"两个入口没合并干净" ——
     * 下一个人顺手把 {@code AdminTools} 指到分页那条上, 编译通过、接口 200、
     * 界面上一个字都不变, 只是助手开始告诉管理员「平台一共有 30 个用户」,
     * 以及把周报的 byRole 统计变成"最新 30 个人的构成". 这两句话错得没有任何痕迹.
     *
     * <p>所以断言的是"报出去的总数与表里的真实行数一致", 而不是某个具体数字:
     * 本类里用户表从来没被清空过(每个用例只清自己灌的那批), 总数必须现数.
     */
    @Test
    @DisplayName("助手侧 list_users / 周报看到的是全体用户, 不是被截断的那 30 个")
    void agentAdminToolsSeeEveryUser() throws Exception {
        seedUsersForPaging(35);
        // 管理员自己也是一行, 所以**先**把他存进去再数总数 —— 顺序反过来会让
        // 期望值比实际少 1, 而"少 1"看起来像某种正常的截断, 很容易被当成对的
        User admin = userRepository.save(User.builder()
                .username("ad" + UUID.randomUUID().toString().substring(0, 8))
                .password("x")
                .role("ADMIN")
                .status("ACTIVE")
                .build());
        int all = userRowCount();
        assertThat(all).as("这条用例要有超过 30 个用户才分得出全量与截断").isGreaterThan(30);

        Map<?, ?> listed = (Map<?, ?>) toolRegistry.find("list_users")
                .getExecutor().execute(null, admin);
        Map<?, ?> breakdown = (Map<?, ?>) ((Map<?, ?>) toolRegistry.find("weekly_ops_report")
                .getExecutor().execute(null, admin)).get("userBreakdown");

        // 明细是截过的(工具说明里就写着"只返回最近 30 条"), 但**总数**不能跟着被截
        assertThat(listed.get("total")).isEqualTo(all);
        assertThat((List<?>) listed.get("list")).hasSizeLessThan(all);
        assertThat(breakdown.get("total")).isEqualTo(all);

        int byRoleSum = ((Map<?, ?>) breakdown.get("byRole")).values().stream()
                .mapToInt(v -> ((Number) v).intValue()).sum();
        assertThat(byRoleSum).as("构成统计要覆盖全体, 否则它只是'前 30 个人的构成'").isEqualTo(all);
    }

    // ========== 管理端用户详情 ==========

    /**
     * 用户详情页的代价是**常数** 9 条语句, 与这个账号有多少数据无关。
     *
     * <p>9 = findById 1 + 四个 count 4 + 三个小列表各 1(追番 / 评论 / 账本) + 番剧名
     * 一次 {@code findAllById}。灌 60 条追番与 60 条评论, 断言仍然是 9 —— 追番 500 部
     * 的人打开这一页的代价与追番 3 部的人一样。
     *
     * <p><b>两个方向的错法都要被这个数字挡住。</b> 多出来说明有人在循环里补查询
     * (每条追番单独查一次番剧名, 或者读评论时碰了 {@code r.getUser()} 触发懒加载);
     * 少下去则说明哪一块根本没查 —— 比如把四个 count 合并成一个"全读回来在内存里数",
     * 那样数字小了、页面看着也对, 只是追番多的人打开会卡。
     *
     * <p><b>不要为了让它好写就改成 {@code <= 9} 或者 {@code isBetween(8, 9)}。</b>
     * 空集合那条路确实少一条({@code findAllById} 收到空集合时 Spring Data 直接返回空表、
     * 不发 SQL), 但这条用例灌了数据, 就该是 9; 放宽之后它不再守卫任何东西。同理,
     * 也不要在 {@code getUserDetail} 里加"两个列表都空就提前返回"的早退去凑常数。
     */
    @Test
    @DisplayName("用户详情: 不论灌多少数据都是 9 条语句")
    void userDetailCostsAConstantNumberOfQueries() {
        seedTrackings(60);
        seedReviewsBy(60, user);
        AtomicReference<Map<String, Object>> holder = new AtomicReference<>();

        long statements = statementsFor(() -> holder.set(adminService.getUserDetail(user.getId())));

        // 三个列表都封顶在 DETAIL_LIST_LIMIT, 所以"数据多"不该让代价变大
        assertThat((List<?>) holder.get().get("trackings")).hasSize(20);
        assertThat((List<?>) holder.get().get("reviews")).hasSize(20);
        assertThat(statements)
                .as("1 findById + 4 count + 3 取页 + 1 番剧名")
                .isEqualTo(9);
    }

    /**
     * 四个计数**不是**把行读回来在内存里数的。
     *
     * <p>与上面那条是同一件事的两面, 但这条更直接: 灌 60 条追番, 断言这次调用读进来的
     * 实体数远小于 60。少了它, 把 {@code countByUser} 改成
     * {@code findByUserOrderByUpdatedAtDesc(u).size()} 之后**语句数一条都不变**
     * (还是那句查追番), 只有实体数会暴露出来 —— 而那正是"追番 500 部的人打开卡一下"
     * 那个问题的形状。
     *
     * <p>上界取 60 而不是"20 上下": 封顶那一页本身就要读回 20 条追番和 20 部番剧
     * (番剧名与封面), 加上用户是 41 个上下, 而这个数字会随 {@code DETAIL_LIST_LIMIT}
     * 变 —— 钉死它等于把用例挂在那个常量上。全读回来的写法是 120 个上下, 两者差得很开,
     * 取 60 这个中间值就够了, 且与那个常量无关。
     */
    @Test
    @DisplayName("用户详情: 四个计数是库里的聚合, 不是把行读回来在内存里数")
    void userDetailCountsDoNotLoadRows() {
        seedTrackings(60);

        Statistics stats = statsCleared();
        Map<String, Object> detail = adminService.getUserDetail(user.getId());
        long loaded = stats.getEntityLoadCount();

        assertThat(((Map<?, ?>) detail.get("counts")).get("trackings")).isEqualTo(60L);
        assertThat(loaded)
                .as("60 条追番全读回来是 120 个上下的实体; 封顶那一页只该是 40 个上下")
                .isLessThan(60);
    }

    /**
     * 灌 n 条由指定作者写的短评, 每条配一部本地已缓存的番剧 —— 详情页要能取到名字.
     *
     * <p>番剧 id 用 {@code SUBJECT_BASE + 1000} 往后排: {@link #seedTrackings} 占的是
     * {@code SUBJECT_BASE} 开头的连续区间, 而 {@code anime.id} 是主键 —— 两条种子用同一段
     * id 的话, 同一个用例里先灌追番再灌评论会在第二条 insert 上撞主键, 报的却是
     * "番剧重复", 看着与详情页毫无关系.
     */
    private void seedReviewsBy(int n, User author) {
        for (int i = 0; i < n; i++) {
            int subjectId = SUBJECT_BASE + 1000 + i;
            jdbc.update("INSERT INTO anime (id, title, tags) VALUES (?, ?, ?)",
                    subjectId, "番" + i, "科幻");
            jdbc.update("INSERT INTO review (user_id, subject_id, rating, content, created_at) "
                            + "VALUES (?, ?, 8, ?, ?)",
                    author.getId(), subjectId, "c" + i,
                    new java.sql.Timestamp(java.sql.Timestamp.valueOf("2030-01-01 00:00:00").getTime()
                            + i * 60_000L));
        }
    }

}
