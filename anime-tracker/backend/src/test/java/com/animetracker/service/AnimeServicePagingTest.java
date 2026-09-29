package com.animetracker.service;

import com.animetracker.config.BangumiApiProperties;
import com.animetracker.config.RankingProperties;
import com.animetracker.entity.Anime;
import com.animetracker.repository.AnimeRepository;
import com.animetracker.repository.AnimeTagRepository;
import com.animetracker.repository.EpisodeRepository;
import com.animetracker.repository.TagRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyDouble;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * 分页参数的边界处理.
 *
 * <p>这一组盯的是「参数越界会把整条调用链炸成 500」这件事本身, 而不是越界值从哪来.
 * 控制器那层已经有 @Min/@Max 挡着网页请求, 但绕过控制器的调用方(Agent 工具、
 * 以后的定时任务、内部互调)走的是同一个 service 方法, 它们没有注解保护.
 * service 这一层再夹一道, 越界就退化成「第一页」, 而不是把栈里其它东西一起炸掉.
 *
 * <p>不写成 @SpringBootTest 是刻意的: 这里要验的是几行算术, 不该顺带启动
 * 整个应用、连数据库、跑一遍启动预加载器(它会联网). 用 mock 把仓库喂成固定
 * 的几行数据, 边界就变成确定的, 也不依赖任何外部服务.
 */
class AnimeServicePagingTest {

    private static final int TOTAL = 5;

    private AnimeRepository animeRepository;
    private AnimeService animeService;

    @BeforeEach
    void setUp() {
        animeRepository = mock(AnimeRepository.class);
        animeService = new AnimeService(
                animeRepository,
                mock(EpisodeRepository.class),
                mock(TagRepository.class),
                mock(AnimeTagRepository.class),
                mock(BangumiApiClient.class),
                mock(BangumiApiProperties.class),
                new RankingProperties());

        // 不带关键词那条分支只读这一个方法, 不会联网.
        // 参数用 anyDouble(): 这里要验的是分页算术, 与加权参数取多少无关 ——
        // 权重本身对不对由 AnimeRankingIntegrationTest 打真库验.
        when(animeRepository.findByWeightedScoreDesc(anyDouble(), anyDouble()))
                .thenReturn(fiveAnime());
    }

    private static List<Anime> fiveAnime() {
        return List.of(
                Anime.builder().id(1).title("a").build(),
                Anime.builder().id(2).title("b").build(),
                Anime.builder().id(3).title("c").build(),
                Anime.builder().id(4).title("d").build(),
                Anime.builder().id(5).title("e").build());
    }

    @SuppressWarnings("unchecked")
    private List<Anime> listOf(Map<String, Object> result) {
        return (List<Anime>) result.get("list");
    }

    private static List<Integer> idsOf(List<Anime> list) {
        return list.stream().map(Anime::getId).toList();
    }

    // ========== 正向: 正常翻页 ==========

    @Test
    @DisplayName("正常翻页: 第 2 页每页 2 条, 取到第 3、4 条")
    void pagesNormally() {
        Map<String, Object> result = animeService.searchAnime("", 2, 2);

        assertThat(idsOf(listOf(result))).containsExactly(3, 4);
        assertThat(result.get("total")).isEqualTo(TOTAL);
        assertThat(result.get("page")).isEqualTo(2);
    }

    @Test
    @DisplayName("最后一页不足一整页时, 只返回剩下的那些, 不报错")
    void lastPartialPageIsFine() {
        assertThat(idsOf(listOf(animeService.searchAnime("", 3, 2)))).containsExactly(5);
    }

    // ========== 越界: 夹回合法区间而不是抛异常 ==========

    /**
     * page=0 是最常见的一种写错(页码从 0 开始计数的地方很多).
     * 改动前它会算出 start=-20, 走到 subList(-20, 0) 直接 IndexOutOfBoundsException,
     * 对外是一个 500 —— 调用方只会以为服务端挂了.
     */
    @Test
    @DisplayName("page=0 当成第 1 页, 而不是 subList(-20, 0) 越界")
    void zeroPageDegradesToFirstPage() {
        assertThatCode(() -> animeService.searchAnime("", 0, 20)).doesNotThrowAnyException();

        Map<String, Object> result = animeService.searchAnime("", 0, 20);
        assertThat(idsOf(listOf(result))).containsExactly(1, 2, 3, 4, 5);
        assertThat(result.get("page")).as("回报的是夹过之后的值").isEqualTo(1);
    }

    @Test
    @DisplayName("负页码当成第 1 页")
    void negativePageDegradesToFirstPage() {
        assertThat(idsOf(listOf(animeService.searchAnime("", -7, 20)))).containsExactly(1, 2, 3, 4, 5);
    }

    @Test
    @DisplayName("limit=0 或负数当成 1 条, 而不是 subList(0, 0) 之外的各种越界")
    void nonPositiveLimitDegradesToOne() {
        assertThat(idsOf(listOf(animeService.searchAnime("", 1, 0)))).containsExactly(1);
        assertThat(idsOf(listOf(animeService.searchAnime("", 1, -5)))).containsExactly(1);
    }

    /**
     * 这一条是加 long 的唯一理由.
     *
     * <p>@Min(1) 只封了下界, page 本身没有上界; 而 (page-1)*limit 是 int 乘法,
     * page=Integer.MAX_VALUE 时它会溢出成负数 —— 于是又绕回「负起点」那个越界,
     * 校验注解拦不住, 因为传进来的页码确实「不小于 1」.
     */
    @Test
    @DisplayName("超大页码: 乘法溢出也不能变成负起点, 结果是空的一页")
    void hugePageDoesNotOverflowIntoANegativeStart() {
        assertThatCode(() -> animeService.searchAnime("", Integer.MAX_VALUE, 20))
                .doesNotThrowAnyException();

        assertThat(listOf(animeService.searchAnime("", Integer.MAX_VALUE, 20))).isEmpty();
    }

    @Test
    @DisplayName("翻过尾页返回空列表, 不报错")
    void pagePastTheEndIsEmpty() {
        assertThat(listOf(animeService.searchAnime("", 9999, 20))).isEmpty();
    }

    // ========== 空数据源 ==========

    @Test
    @DisplayName("一条数据都没有时, 任何分页参数都返回空列表")
    void emptySourceIsAlwaysEmpty() {
        when(animeRepository.findByWeightedScoreDesc(anyDouble(), anyDouble())).thenReturn(List.of());

        assertThat(listOf(animeService.searchAnime("", 1, 20))).isEmpty();
        assertThat(listOf(animeService.searchAnime("", 0, 0))).isEmpty();
        assertThat(listOf(animeService.searchAnime("", Integer.MAX_VALUE, Integer.MAX_VALUE))).isEmpty();
    }

    // ========== 有 @Max 保护的上界也不该在 service 层炸 ==========

    @Test
    @DisplayName("limit 大到离谱时按实际条数返回, 不会 subList 越界")
    void oversizedLimitReturnsEverything() {
        assertThat(idsOf(listOf(animeService.searchAnime("", 1, Integer.MAX_VALUE))))
                .containsExactly(1, 2, 3, 4, 5);
    }

    @Test
    @DisplayName("返回的 total 是总数而不是当页条数, 前端靠它算页码")
    void totalIsTheWholeCountNotThePageSize() {
        Map<String, Object> result = animeService.searchAnime("", 1, 2);

        assertThat(listOf(result)).hasSize(2);
        assertThat(result.get("total")).isEqualTo(TOTAL);
        // 顺带钉住 map 的键名: 前端和 ToolViews 都按这几个字面量取值
        assertThat(result).containsOnlyKeys("list", "total", "page");
    }

    @Test
    @DisplayName("关键词分支同样受保护 (两条分支共用一个分页函数)")
    void keywordBranchIsProtectedToo() {
        when(animeRepository.searchByKeywordPattern(any())).thenReturn(fiveAnime());

        // 本地 5 条 >= limit 2, 直接就在本地分页返回, 不会去碰 API mock
        assertThatCode(() -> animeService.searchAnime("番", 0, 2)).doesNotThrowAnyException();
        assertThat(idsOf(listOf(animeService.searchAnime("番", 0, 2)))).containsExactly(1, 2);
    }

    // ========== 筛选接口的分页 ==========
    //
    // 筛选走的是另一个仓储方法(按 rank 排), 而且在分页前还要过一遍筛选条件,
    // 所以它不能只靠上面那组用例覆盖到.

    /** 前三条 2026-01, 后两条 2026-04 */
    private static List<Anime> fiveAnimeWithSeasons() {
        return List.of(
                Anime.builder().id(1).title("a").season("2026-01").build(),
                Anime.builder().id(2).title("b").season("2026-01").build(),
                Anime.builder().id(3).title("c").season("2026-01").build(),
                Anime.builder().id(4).title("d").season("2026-04").build(),
                Anime.builder().id(5).title("e").season("2026-04").build());
    }

    /**
     * 顺序必须是**先筛、后排、再切页**.
     *
     * <p>先切页的话, 第 2 页会切在未筛选的 5 条上得到 [3,4,5], 再用条件筛只剩 [3],
     * total 也变成 1 —— 前端据此算出来的总页数是错的. 这条用例用一个"筛选后
     * 恰好跨页"的数据集把这个顺序钉死: 三条 2026-01 的番, 每页 2 条.
     */
    @Test
    @DisplayName("筛选后分页: 第 2 页只拿到筛选结果里的第 3 条, total 是筛选后的 3 而不是 5")
    void filterPagingFiltersBeforeItSlices() {
        when(animeRepository.findByOrderByRankAsc()).thenReturn(fiveAnimeWithSeasons());

        Map<String, Object> firstPage = animeService.getFilteredPage(null, "2026-01", null, null, null, 1, 2);
        assertThat(idsOf(listOf(firstPage))).containsExactly(1, 2);
        assertThat(firstPage.get("total")).isEqualTo(3);

        Map<String, Object> secondPage = animeService.getFilteredPage(null, "2026-01", null, null, null, 2, 2);
        assertThat(idsOf(listOf(secondPage))).containsExactly(3);
        assertThat(secondPage.get("total")).isEqualTo(3);

        // 键名与搜索接口一模一样, 前端两个接口共用同一段读取逻辑
        assertThat(firstPage).containsOnlyKeys("list", "total", "page");
    }

    @Test
    @DisplayName("筛选接口同样夹越界值: page=0 退化成第 1 页, 超大页码给空页而不是抛异常")
    void filterPagingClampsOutOfRangeValues() {
        when(animeRepository.findByOrderByRankAsc()).thenReturn(fiveAnimeWithSeasons());

        assertThatCode(() -> animeService.getFilteredPage(null, null, null, null, null, 0, 2))
                .doesNotThrowAnyException();
        assertThat(idsOf(listOf(animeService.getFilteredPage(null, null, null, null, null, 0, 2))))
                .containsExactly(1, 2);
        assertThat(listOf(animeService.getFilteredPage(null, null, null, null, null, Integer.MAX_VALUE, 20)))
                .isEmpty();
        assertThat(listOf(animeService.getFilteredPage(null, null, null, null, null, 99, 20)))
                .isEmpty();
    }

    @Test
    @DisplayName("筛选一条都不匹配时返回空页, 但 total 仍是 0 而不是 null")
    void filterPagingOnEmptyResult() {
        when(animeRepository.findByOrderByRankAsc()).thenReturn(fiveAnimeWithSeasons());

        Map<String, Object> result = animeService.getFilteredPage(null, "2049-07", null, null, null, 1, 20);
        assertThat(listOf(result)).isEmpty();
        assertThat(result.get("total")).isEqualTo(0);
    }
}
