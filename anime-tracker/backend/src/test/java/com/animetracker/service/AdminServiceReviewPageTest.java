package com.animetracker.service;

import com.animetracker.entity.Anime;
import com.animetracker.entity.Review;
import com.animetracker.entity.User;
import com.animetracker.repository.AdminActionLogRepository;
import com.animetracker.repository.AnimeRepository;
import com.animetracker.repository.EpisodeWatchedRepository;
import com.animetracker.repository.ReviewRepository;
import com.animetracker.repository.ReviewReportRepository;
import com.animetracker.repository.TrackingRepository;
import com.animetracker.repository.UserRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.mockito.ArgumentCaptor;
import org.springframework.data.domain.Pageable;
import org.springframework.security.crypto.password.PasswordEncoder;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 管理端评论列表: **筛选条件怎么翻译成仓储参数**, 以及**页码/每页条数怎么夹**.
 *
 * <p>这一层测的是"翻译", 不是"匹配". 「关键词真的命中了正文与用户名」「评分档位真的
 * 圈住了 1–4」是 JPQL 的事, 归 {@code QueryCountIntegrationTest} 那几条在真库上跑的
 * 用例 —— 在 mock 上断这类事只会得到"我 mock 了什么就断言什么"的空转.
 *
 * <p>这里能测而真库测不出的, 恰恰是**翻译错的时候没有任何东西会报错**:
 *
 * <ul>
 *   <li>关键词的空串若没被挡掉, 会变成 {@code %%}(匹配全部) —— 页面看着正常, 只是
 *       筛选框形同虚设;</li>
 *   <li>档位表若把 8–10 写成 8–9, "好评"会安静地少一条, 没人会发现;</li>
 *   <li>排序白名单若漏了一项, 点那个表头会**静默地**按主键排 —— 表头 caret 指着
 *       "按赞数", 行却是按时间来的;</li>
 *   <li>越界页若照旧发一条 OFFSET 9999*20 的查询, 结果一样是空页, 只是每次翻到底
 *       都白扫一遍全表.</li>
 * </ul>
 */
class AdminServiceReviewPageTest {

    private ReviewRepository reviewRepository;
    private AnimeRepository animeRepository;
    private ReviewReportRepository reviewReportRepository;
    private AdminService adminService;

    @BeforeEach
    void setUp() {
        reviewRepository = mock(ReviewRepository.class);
        animeRepository = mock(AnimeRepository.class);
        reviewReportRepository = mock(ReviewReportRepository.class);
        // 默认返回空列表而不是 null: toAdminReviewRows 会直接对它 .stream(),
        // 而 Mockito 对 List 返回值的默认就是空列表 —— 显式写出来是为了让"这里有个
        // 会被解引用的返回值"在下一个人眼里是可见的
        when(animeRepository.findAllById(any())).thenReturn(List.of());
        // 计数给一个够大的数, 好让取页那条**真的发出去** —— 给 0 的话
        // offset >= total 会提前返回, 于是"调了哪一条取页方法"这类断言全部空转.
        // 需要 0 / 越界的用例各自覆盖这个桩.
        when(reviewRepository.countAdminReviews(any(), any(), any(), anyBoolean()))
                .thenReturn(1000L);
        // 举报摘要是**批量**查的(一条 IN), toAdminReviewRows 会直接对它 .stream()。
        // 与上面 animeRepository 那个桩同一条理由: 显式写出"这里有个会被解引用的返回值"。
        when(reviewReportRepository.findPendingSummaries(any())).thenReturn(List.of());
        adminService = new AdminService(
                mock(UserRepository.class),
                reviewRepository,
                mock(TrackingRepository.class),
                animeRepository,
                mock(AdminActionLogRepository.class),
                reviewReportRepository,
                mock(EpisodeWatchedRepository.class),
                new IsolatedInsert(),
                mock(PasswordEncoder.class));
    }

    /** 一次"没有筛选条件"的取页, 只关心它往仓储传了什么 */
    private Map<String, Object> fetch(String keyword, String rating, String sort, String order,
                                      int page, int limit) {
        return adminService.getReviewPage(keyword, rating, null, sort, order, page, limit);
    }

    /** 同上, 但只填"只看被举报的"那一维 —— 其余各维一律不筛 */
    private Map<String, Object> fetchReported(String reported) {
        return adminService.getReviewPage(null, null, reported, null, null, 1, 20);
    }

    /** 计数那条收到的关键词模式串 */
    private String capturedKeywordPattern() {
        ArgumentCaptor<String> captor = ArgumentCaptor.forClass(String.class);
        verify(reviewRepository).countAdminReviews(captor.capture(), any(), any(), anyBoolean());
        return captor.getValue();
    }

    // ========== 关键词 ==========

    /**
     * 关键词 → LIKE 模式串, 空白一律当"没筛".
     *
     * <p>{@code "   "} 那一条不是洁癖: 少了 trim, 它会变成 {@code "%   %"} —— 一个
     * 看上去"没筛"、实际什么都不匹配的条件, 而管理员只会觉得"这站里没有这条评论".
     */
    @ParameterizedTest(name = "[{0}] → {1}")
    @CsvSource(nullValues = "NULL", value = {
            "NULL,  NULL",
            "'',    NULL",
            "'   ', NULL",
            "ab,    '%ab%'",
            // 通配符与转义符必须被转义: 不转的话用户输一个 % 就等于"列出全部",
            // 而 ESCAPE '!' 这个约定是与 JPQL 那侧成对的, 单看一边看不出问题
            "'100%', '%100!%%'",
            "'a_b',  '%a!_b%'",
            "'a!b',  '%a!!b%'",
            "'  ab  ', '%ab%'",
    })
    @DisplayName("关键词: 空白当没筛, 通配符与转义符被转义")
    void keywordBecomesAnEscapedContainPattern(String keyword, String expected) {
        fetch(keyword, null, null, null, 1, 20);

        assertThat(capturedKeywordPattern()).isEqualTo(expected);
    }

    // ========== 评分档位 ==========

    /**
     * 三档的闭区间, 以及"认不出来就当没筛".
     *
     * <p>档位值来自一张常量表, 不来自请求 —— 所以这里断的是**这张表本身**,
     * 而不是"用户输入被正确校验了". 大小写与空白那两行是给手改过的 URL 留的:
     * {@code ?rating=LOW} 落回"不筛"比落回 400 更合适(理由同排序).
     */
    @ParameterizedTest(name = "rating=[{0}] → [{1}, {2}]")
    @CsvSource(nullValues = "NULL", value = {
            "low,     1,  4",
            "mid,     5,  7",
            "high,    8, 10",
            "LOW,     1,  4",
            "' high ', 8, 10",
            "NULL,    NULL, NULL",
            "'',      NULL, NULL",
            "NOPE,    NULL, NULL",
            // 差一点就写错的两个: 10 是闭区间的右端(写成 9 会安静地少一条好评),
            // 而 4/5 之间不能有缝(1–4 与 5–7 拼起来必须正好覆盖 1..10)
            "bogus,   NULL, NULL",
    })
    @DisplayName("评分档位: 三档的闭区间, 认不出来的一律当没筛")
    void ratingBandIsAFixedClosedInterval(String rating, Integer min, Integer max) {
        fetch(null, rating, null, null, 1, 20);

        verify(reviewRepository).countAdminReviews(isNull(), eq(min), eq(max), eq(false));
    }

    // ========== 只看被举报的 ==========

    /**
     * {@code reported} → 布尔开关: <b>只有字面量 {@code true} 才算数</b>, 其余一律当"不筛"。
     *
     * <p>为什么这里是一个 boolean 而不是把 {@code reported} 原样传给仓储: 仓储上那个参数
     * 参与的是 {@code :reported = FALSE OR EXISTS (...)} 这样一个**布尔守卫**(与
     * {@code AnimeQueries} 的题材/标签筛选同一个形状), 它必须是基本类型 —— 写成
     * {@code :reported IS NULL} 那种参数为 null 的判据在 PostgreSQL 上是个 prepare 期的坑。
     *
     * <p>三条边界各有各的失效方式, 所以逐条钉住:
     *
     * <ul>
     *   <li>{@code null}(默认请求, 也就是"不带这个参数")必须落回<b>不筛</b> —— 落到 true
     *       的话, 默认那一页会**只显示被举报的**, 而界面上没有任何东西解释为什么少了
     *       一大半评论。这条是本类 {@code theDefaultRequestIsAWellFormedPage}
     *       那个「一个参数都不带」的用例在服务层的对应物;</li>
     *   <li>{@code "false"} 也必须是不筛 —— 它与 {@code null} 在这里**恰好等价**, 但那是
     *       结果, 不是理由: 判据是"字面量 true 才算数", 而不是"false 才算假"。
     *       写成 {@code Boolean.parseBoolean} 就没有这个区别, 但这两种写法在
     *       {@code "1"} / {@code "yes"} 上会分道扬镳 —— 见下一条;</li>
     *   <li>{@code "1"} / {@code "yes"} / 拼错的值**静默当不筛**, 不报 400 —— 这是管理端
     *       读路径的 doctrine(见 {@code AdminService.getUserPage} 那段注释): 筛错一个值
     *       的后果是结果集放宽一点, 管理员看得出不对。写路径(举报理由)反过来, 那里回 400。
     *       这一条把那个不对称也钉住了: 同一件"未知取值"的事, 两个方向的期望是相反的。</li>
     * </ul>
     */
    @ParameterizedTest(name = "reported=<{0}> → 只看被举报={1}")
    @CsvSource({
            "null,   false",
            "false,  false",
            "FALSE,  false",
            "'',     false",
            "'  ',   false",
            "1,      false",
            "yes,    false",
            "ture,   false",
            "true,   true",
            "TRUE,   true",
            "' true ', true",
    })
    @DisplayName("只看被举报: 只有字面量 true 才打开, 其余(含未知值)一律当不筛")
    void onlyTheLiteralTrueTurnsTheReportedFilterOn(String reported, boolean expected) {
        fetchReported("null".equals(reported) ? null : reported);

        verify(reviewRepository).countAdminReviews(any(), any(), any(), eq(expected));
    }

    // ========== 排序 / 顺序 ==========

    /**
     * 六种组合各自调到**那一条**仓储方法.
     *
     * <p>这一条是这类改动里最容易静默写错的地方: 六条方法的 JPQL 只差一个 ORDER BY
     * 常量, 调错了照样返回六行、照样 200, 只有"表头说按赞数、行却按时间排"这一个
     * 症状, 而那个症状单看接口是看不出来的.
     */
    @ParameterizedTest(name = "sort={0} order={1}")
    @CsvSource({
            "id,      asc",
            "id,      desc",
            "likes,   asc",
            "likes,   desc",
            "replies, asc",
            "replies, desc",
    })
    @DisplayName("排序: 六种组合各自调到对应那条仓储方法")
    void eachSortCombinationCallsItsOwnRepositoryMethod(String sort, String order) {
        fetch(null, null, sort, order, 1, 20);

        verify(reviewRepository, times(1)).countAdminReviews(any(), any(), any(), anyBoolean());
        verifyNoMoreInteractionsOnPageMethodsOver(reviewRepository, expectedMethod(sort, order));
    }

    /**
     * 认不出来的 {@code sort} **不报 400**, 落回主键那一列 —— 但 {@code order} 照常生效.
     *
     * <p>与本类既有的 {@code getUserPage} 同一条规矩: 排序是展示偏好, 而 {@code sort}
     * 会出现在用户分享出去的链接里 —— 为一个拼错的值让整张表打不开, 代价远大于按默认列
     * 显示. 这里连"不抛异常"一起断, 因为"落回默认"这个行为本身不会报错, 会报错的是
     * 下一个人"顺手"把它改成抛 400.
     *
     * <p><b>两列各管各的</b> —— 第 2 行({@code sort} 缺省 + {@code order=asc})落在
     * {@code ID_ASC} 上: 只认不出列就退回主键列, 顺序按用户说的来. 写成"认不出 sort
     * 就整个退回 id desc"会把一个明确的「我要正序」也吞掉.
     *
     * <p>大小写那两行也不是凑数: 白名单认的是字面量, {@code ID} / {@code ASC} 都**认不出来**.
     * 哪天有人给它加个 {@code toLowerCase}, 这两行会红 —— 而那是**对**的红: 前端传的就是
     * 小写字面量, 多一种能匹配的写法只会让"URL 上该写什么"这件事有两个答案.
     */
    @ParameterizedTest(name = "sort=[{0}] order=[{1}] → {2}")
    @CsvSource(nullValues = "NULL", value = {
            "NULL,        NULL,     ID_DESC",
            "NULL,        asc,      ID_ASC",
            "bogus,       NULL,     ID_DESC",
            "bogus,       sideways, ID_DESC",
            "'',          '',       ID_DESC",
            "SORTBYVIBES, DESC,     ID_DESC",
            "ID,          ASC,      ID_DESC",
            "Likes,       desc,     ID_DESC",
    })
    @DisplayName("排序: 认不出来的列落回主键, 但 order 照常生效, 都不抛异常")
    void unknownSortFallsBackToTheIdColumnWhileOrderStillApplies(String sort, String order,
                                                                 PageMethod expected) {
        assertThatCode(() -> fetch(null, null, sort, order, 1, 20))
                .doesNotThrowAnyException();

        verify(reviewRepository).countAdminReviews(any(), any(), any(), anyBoolean());
        verifyNoMoreInteractionsOnPageMethodsOver(reviewRepository, expected);
    }

    // ========== 页码与页大小 ==========

    /**
     * {@code limit} 两侧的夹取.
     *
     * <p>下界用**默认值 20** 而不是 1: 一页一行既没有用, 又和"分页坏了"长得一模一样.
     * 上界 100 正常由 controller 的 {@code @Max(100)} 先拦成 400, 这里是兜底 ——
     * service 不该假设自己只被那一个入口调用(Agent 工具也走这里).
     */
    @ParameterizedTest(name = "limit={0} → 每页 {1}")
    @CsvSource({
            "0,    20",
            "-5,   20",
            "1,    1",
            "20,   20",
            "100,  100",
            "101,  100",
            "500,  100",
    })
    @DisplayName("每页条数: 小于 1 用默认 20, 大于 100 夹到 100")
    void limitIsClampedOnBothSides(int limit, int expectedSize) {
        fetch(null, null, null, null, 1, limit);

        assertThat(capturedPageable().getPageSize()).isEqualTo(expectedSize);
        assertThat(capturedPageable().getPageNumber()).as("页码是 1 基的, 交给 Spring 要减 1")
                .isZero();
    }

    /**
     * 越界页: 报**真实的** total, 但一条取页查询都不发.
     *
     * <p>两件事各有各的失效方式. total 报 0 的话, 前端按 {@code ceil(total/limit)} 算出来的
     * 翻页控件会凭空少几页, 用户从最后一页往回点就回不去了; 少那道提前返回, 一个
     * {@code page=9999} 会真的带着巨大的 OFFSET 发给数据库, 结果一模一样, 只是白扫一遍.
     */
    @Test
    @DisplayName("越界页: 空列表 + 真实 total, 取页那条根本不该发")
    void outOfRangePageReportsTheRealTotalButFetchesNothing() {
        when(reviewRepository.countAdminReviews(any(), any(), any(), anyBoolean())).thenReturn(7L);

        Map<String, Object> result = fetch(null, null, null, null, 9999, 20);

        assertThat((List<?>) result.get("list")).isEmpty();
        assertThat(result.get("total")).isEqualTo(7);
        assertThat(result.get("page")).as("回显的是夹取后的页码").isEqualTo(9999);
        verify(reviewRepository, never()).findAdminReviewPageByIdDesc(any(), any(), any(), anyBoolean(), any());
        verify(reviewRepository, never()).findAdminReviewPageByIdAsc(any(), any(), any(), anyBoolean(), any());
        // 一条行都没有, 番剧名那次批量查询也不该发出去
        verify(animeRepository, never()).findAllById(any());
    }

    /**
     * 一条评论都没有(而不是页码越界)也走同一条出口.
     *
     * <p>看着像重复, 其实两码事: 上一条的 offset 是 9999*20, 这一条 offset 是 0 ——
     * 挡住前者的可能是"offset > MAX_SQL_OFFSET", 而这里只有 {@code offset >= total}
     * 拦得住. 两个条件合成一条出口, 任何一个写反都会让空库在首页多发一条查询.
     */
    @Test
    @DisplayName("空库: 首页也是 0 行, total 0, 取页不发")
    void emptyTableOnTheFirstPageFetchesNothingEither() {
        when(reviewRepository.countAdminReviews(any(), any(), any(), anyBoolean())).thenReturn(0L);

        Map<String, Object> result = fetch(null, null, null, null, 1, 20);

        assertThat((List<?>) result.get("list")).isEmpty();
        assertThat(result.get("total")).isEqualTo(0);
        verify(reviewRepository, never()).findAdminReviewPageByIdDesc(any(), any(), any(), anyBoolean(), any());
    }

    /**
     * 页码 0 或负数当第 1 页, 而不是把负数交给 Spring.
     *
     * <p>{@code PageRequest.of(-1, 20)} 会直接抛 {@code IllegalArgumentException} ——
     * 一个 500. 而 {@code ?page=0} 是用户手改地址栏时最常见的一种, 不该变成服务器故障.
     */
    @ParameterizedTest(name = "page={0} → 第 {1} 页")
    @CsvSource({"0, 0", "-3, 0", "1, 0", "2, 1", "5, 4"})
    @DisplayName("页码: 小于 1 当第 1 页, 交给 Spring 的序号是页码减 1")
    void pageIsClampedAtOne(int page, int expectedZeroBased) {
        fetch(null, null, null, null, page, 20);

        assertThat(capturedPageable().getPageNumber()).isEqualTo(expectedZeroBased);
    }

    // ========== 行的形状 ==========

    /**
     * 行里必须有 {@code animeTitle} 与 {@code replyCount} 两个键.
     *
     * <p>这两个正是这一轮补上去的. 前者改前**根本不存在**(界面上只有一个裸的
     * {@code subjectId}, 管理员看不出是哪部番), 后者改前存在但**后端从没发过** ——
     * 前端那一格恒显示 {@code undefined}, 而前端的 mock 自己造了这个键, 于是用例
     * 一直绿. 所以这里两个键都点名断言, 而不是只断"行不为空".
     */
    @Test
    @DisplayName("行: animeTitle 与 replyCount 都在, 作者名与赞数也带上了")
    void rowsCarryTheAnimeTitleAndTheReplyCount() {
        when(reviewRepository.countAdminReviews(any(), any(), any(), anyBoolean())).thenReturn(1L);
        when(reviewRepository.findAdminReviewPageByIdDesc(any(), any(), any(), anyBoolean(), any()))
                .thenReturn(List.of(review(7L, 656083, 8, "正文", 2L, 3L)));
        when(animeRepository.findAllById(any()))
                .thenReturn(List.of(Anime.builder().id(656083).title("某番").build()));

        List<Map<String, Object>> rows = rowsOf(fetch(null, null, null, null, 1, 20));

        assertThat(rows).hasSize(1);
        assertThat(rows.get(0))
                .containsEntry("id", 7L)
                .containsEntry("subjectId", 656083)
                .containsEntry("animeTitle", "某番")
                .containsEntry("username", "bob")
                .containsEntry("rating", 8)
                .containsEntry("content", "正文")
                .containsEntry("likeCount", 2L)
                .containsEntry("replyCount", 3L);
    }

    /**
     * 本地没缓存过那部番时, {@code animeTitle} 是 <b>null</b> 而不是被填成 id 或空串.
     *
     * <p>{@code review.subject_id} 与 {@code anime} 之间**没有外键**, 那部番完全可能还没
     * 被拉进本地库. 这时"这个 id 对应的番剧还没进本地库"本身是真信息, 前端靠它退化成
     * 「番剧 #656083」—— service 这一侧把 null 编成一个假名字, 那个退化分支就永远走不到了.
     *
     * <p>键必须在: 少了它前端走的是 {@code undefined} 分支, 而"没有这个键"与"没缓存过"
     * 在界面上长得一模一样.
     */
    @Test
    @DisplayName("行: 番剧没缓存过时 animeTitle 是 null, 但键必须在")
    void missingAnimeYieldsANullTitleNotAFabricatedOne() {
        when(reviewRepository.countAdminReviews(any(), any(), any(), anyBoolean())).thenReturn(1L);
        when(reviewRepository.findAdminReviewPageByIdDesc(any(), any(), any(), anyBoolean(), any()))
                .thenReturn(List.of(review(7L, 656083, 8, "正文", 0L, 0L)));

        List<Map<String, Object>> rows = rowsOf(fetch(null, null, null, null, 1, 20));

        assertThat(rows.get(0)).containsKey("animeTitle");
        assertThat(rows.get(0).get("animeTitle")).isNull();
    }

    /**
     * 番剧名是**一次**批量查询补齐的, 不是逐行查.
     *
     * <p>逐行查的结果一模一样, 只是 2+N 条语句 —— 这正是
     * {@code QueryCountIntegrationTest} 里"一页 = 3 条"那条在真库上盯着的同一个形状.
     * 在这里再钉一遍是因为它足够便宜, 而且 mock 上更容易看清"调了几次":
     * 真库那侧要数语句, 这里只看调用次数.
     */
    @Test
    @DisplayName("行: 番剧名一次批量查完, 有几行都只查一次")
    void animeTitlesAreResolvedInASingleBatchQuery() {
        when(reviewRepository.countAdminReviews(any(), any(), any(), anyBoolean())).thenReturn(3L);
        when(reviewRepository.findAdminReviewPageByIdDesc(any(), any(), any(), anyBoolean(), any()))
                .thenReturn(List.of(
                        review(1L, 656083, 8, "a", 0L, 0L),
                        review(2L, 656083, 8, "b", 0L, 0L),
                        review(3L, 400602, 8, "c", 0L, 0L)));

        rowsOf(fetch(null, null, null, null, 1, 20));

        // 两条评论挂同一部番, distinct 之后只该有两个 id
        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<Integer>> captor = ArgumentCaptor.forClass(List.class);
        verify(animeRepository, times(1)).findAllById(captor.capture());
        assertThat(captor.getValue()).containsExactly(656083, 400602);
    }

    // ========== 辅助 ==========

    /** 六条取页方法, 用枚举把"sort + order → 哪一条"这件事写死一遍 */
    private enum PageMethod {
        ID_DESC, ID_ASC, LIKES_DESC, LIKES_ASC, REPLIES_DESC, REPLIES_ASC
    }

    private static PageMethod expectedMethod(String sort, String order) {
        boolean asc = "asc".equals(order);
        if ("likes".equals(sort)) {
            return asc ? PageMethod.LIKES_ASC : PageMethod.LIKES_DESC;
        }
        if ("replies".equals(sort)) {
            return asc ? PageMethod.REPLIES_ASC : PageMethod.REPLIES_DESC;
        }
        return asc ? PageMethod.ID_ASC : PageMethod.ID_DESC;
    }

    /**
     * 「除了这一条, 另外五条取页方法一次都没被调用」.
     *
     * <p>不用 {@code verifyNoMoreInteractions}: 那个会把 {@code countAdminReviews} 也
     * 算进来, 而它本来就该被调用过. 六条里逐条排除反而更清楚 —— 顺手把"是哪一条被调了"
     * 一起断掉, 而不是只断"另外几条没被调"(那样六条全没调也能过).
     */
    private static void verifyNoMoreInteractionsOnPageMethodsOver(ReviewRepository repo,
                                                                  PageMethod called) {
        for (PageMethod m : PageMethod.values()) {
            if (m != called) {
                verifyNotCalled(repo, m);
            }
        }
        verifyCalled(repo, called);
    }

    private static void verifyCalled(ReviewRepository repo, PageMethod m) {
        switch (m) {
            case ID_DESC -> verify(repo).findAdminReviewPageByIdDesc(any(), any(), any(), anyBoolean(), any());
            case ID_ASC -> verify(repo).findAdminReviewPageByIdAsc(any(), any(), any(), anyBoolean(), any());
            case LIKES_DESC -> verify(repo).findAdminReviewPageByLikesDesc(any(), any(), any(), anyBoolean(), any());
            case LIKES_ASC -> verify(repo).findAdminReviewPageByLikesAsc(any(), any(), any(), anyBoolean(), any());
            case REPLIES_DESC ->
                    verify(repo).findAdminReviewPageByRepliesDesc(any(), any(), any(), anyBoolean(), any());
            case REPLIES_ASC ->
                    verify(repo).findAdminReviewPageByRepliesAsc(any(), any(), any(), anyBoolean(), any());
        }
    }

    private static void verifyNotCalled(ReviewRepository repo, PageMethod m) {
        switch (m) {
            case ID_DESC -> verify(repo, never()).findAdminReviewPageByIdDesc(any(), any(), any(), anyBoolean(), any());
            case ID_ASC -> verify(repo, never()).findAdminReviewPageByIdAsc(any(), any(), any(), anyBoolean(), any());
            case LIKES_DESC ->
                    verify(repo, never()).findAdminReviewPageByLikesDesc(any(), any(), any(), anyBoolean(), any());
            case LIKES_ASC ->
                    verify(repo, never()).findAdminReviewPageByLikesAsc(any(), any(), any(), anyBoolean(), any());
            case REPLIES_DESC ->
                    verify(repo, never()).findAdminReviewPageByRepliesDesc(any(), any(), any(), anyBoolean(), any());
            case REPLIES_ASC ->
                    verify(repo, never()).findAdminReviewPageByRepliesAsc(any(), any(), any(), anyBoolean(), any());
        }
    }

    /** 取页那条收到的 Pageable */
    private Pageable capturedPageable() {
        ArgumentCaptor<Pageable> captor = ArgumentCaptor.forClass(Pageable.class);
        verify(reviewRepository, times(1)).findAdminReviewPageByIdDesc(any(), any(), any(), anyBoolean(), captor.capture());
        return captor.getValue();
    }

    @SuppressWarnings("unchecked")
    private static List<Map<String, Object>> rowsOf(Map<String, Object> page) {
        return (List<Map<String, Object>>) page.get("list");
    }

    private static Review review(Long id, int subjectId, int rating, String content,
                                 long likeCount, long replyCount) {
        return Review.builder()
                .id(id)
                .user(User.builder().id(900L + id).username("bob").build())
                .subjectId(subjectId)
                .rating(rating)
                .content(content)
                .likeCount(likeCount)
                .replyCount(replyCount)
                .createdAt(LocalDateTime.of(2026, 1, 1, 0, 0))
                .build();
    }
}
