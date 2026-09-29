package com.animetracker.service;

import com.animetracker.config.BangumiApiProperties;
import com.animetracker.dto.BangumiDTO.InfoboxItem;
import com.animetracker.dto.BangumiDTO.SearchResponse;
import com.animetracker.dto.BangumiDTO.SubjectDTO;
import com.animetracker.entity.Anime;
import com.animetracker.repository.AnimeRepository;
import com.animetracker.repository.AnimeTagRepository;
import com.animetracker.repository.EpisodeRepository;
import com.animetracker.repository.TagRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * 搜索的懒回源: 什么时候去问 Bangumi、问第几页、问几次、报多少条.
 *
 * <p>这一组盯的是**请求的形状**, 不是 SQL —— 别名能不能被 LIKE 命中由
 * {@code AnimeSearchQueryIntegrationTest} 在真库上验. 两者缺一不可: SQL 对了但页码传错,
 * 用户翻到第 2 页照样是空的; 页码对了但别名没落进那一列, 第 1 页就是空的.
 *
 * <p>为什么"问几次"值得钉死两次(第 1 页恰好 2 次 / 第 9999 页不超过 5 次): 回源落库不是
 * 只读一下 —— 每个条目都是一次 findById + UPDATE + 重写若干行 anime_tag. 拉重了是白写几百行,
 * 拉多了则是一个公开 GET 能对外打出近万次请求(page 可以从 URL 手填).
 *
 * <p>不启 Spring: 这里要验的是几行控制流, 不该顺带连库、跑启动预加载(它会联网).
 */
class AnimeSearchBackfillTest {

    private static final String KW = "EVA";
    private static final ObjectMapper MAPPER = new ObjectMapper();

    private AnimeRepository animeRepository;
    private BangumiApiClient bangumiApiClient;
    private AnimeService animeService;

    /** cacheAll 真正 save 下去的行 —— 用它当"落库之后本地能查到什么"的答案 */
    private final List<Anime> savedRows = new ArrayList<>();

    @BeforeEach
    void setUp() {
        animeRepository = mock(AnimeRepository.class);
        bangumiApiClient = mock(BangumiApiClient.class);
        savedRows.clear();

        animeService = new AnimeService(
                animeRepository,
                mock(EpisodeRepository.class),
                mock(TagRepository.class),
                mock(AnimeTagRepository.class),
                bangumiApiClient,
                mock(BangumiApiProperties.class));

        when(animeRepository.save(any(Anime.class))).thenAnswer(inv -> {
            Anime a = inv.getArgument(0);
            savedRows.add(a);
            return a;
        });
    }

    // ========== 造数据 ==========

    /** 一段连续的本地行, id 从 from 开始 */
    private static List<Anime> localRows(int from, int count) {
        List<Anime> out = new ArrayList<>();
        for (int i = 0; i < count; i++) {
            out.add(Anime.builder().id(from + i).title("t" + (from + i)).build());
        }
        return out;
    }

    /** 一条带「别名」infobox 的远端条目 —— 别名正是本地搜不到、要靠回源补上的那个东西 */
    private static SubjectDTO remoteWithAlias(int id, String... aliases) {
        StringBuilder json = new StringBuilder("[");
        for (int i = 0; i < aliases.length; i++) {
            if (i > 0) json.append(',');
            json.append("{\"v\":\"").append(aliases[i]).append("\"}");
        }
        json.append(']');

        InfoboxItem item = new InfoboxItem();
        item.setKey("别名");
        try {
            item.setValue(MAPPER.readTree(json.toString()));
        } catch (Exception e) {
            throw new IllegalStateException("测试用的 JSON 写错了", e);
        }

        SubjectDTO dto = new SubjectDTO();
        dto.setId(id);
        dto.setName("新世紀エヴァンゲリオン");
        dto.setInfobox(List.of(item));
        return dto;
    }

    private static SearchResponse response(Integer total, SubjectDTO... data) {
        SearchResponse resp = new SearchResponse();
        resp.setTotal(total);
        resp.setData(new ArrayList<>(List.of(data)));
        return resp;
    }

    @SuppressWarnings("unchecked")
    private List<Anime> listOf(Map<String, Object> result) {
        return (List<Anime>) result.get("list");
    }

    private static List<Integer> idsOf(List<Anime> list) {
        return list.stream().map(Anime::getId).toList();
    }

    /**
     * 本地查询的答案: 第一次给 initial, 之后一律给"已经落库的那些行".
     *
     * <p>这条 stub 复刻的正是回源之后那次**重查** —— 它就是改动前漏掉的那一步:
     * 数据落了库, 却还用同一条只匹配中/日名的 LIKE 去问, 于是界面继续空白.
     */
    private void localThenCached(List<Anime> initial) {
        when(animeRepository.searchByKeywordPattern(any()))
                .thenReturn(initial)
                .thenAnswer(inv -> new ArrayList<>(savedRows));
    }

    // ========== 回源之后真的搜得到 ==========

    @Test
    @DisplayName("本地搜不到 -> 回源 -> 别名落库 -> 重查命中（搜别名的正路）")
    void backfilledAliasBecomesSearchable() {
        localThenCached(List.of());
        when(bangumiApiClient.searchSubjects(eq(KW), anyInt(), anyInt()))
                .thenReturn(response(1, remoteWithAlias(265, "EVA", "Neon Genesis Evangelion")))
                .thenReturn(response(1));

        Map<String, Object> result = animeService.searchAnime(KW, 1, 20);

        assertThat(idsOf(listOf(result))).containsExactly(265);
        assertThat(listOf(result).get(0).getAliases()).contains("EVA");
        assertThat(result.get("total")).isEqualTo(1);
    }

    // ========== 问第几页 ==========

    /**
     * 第 2 页要问 Bangumi 的**第 3 页**, 而且不该再问第 1、2 页.
     *
     * <p>为什么是 3 不是 2: 本地存的是"已经拉过的那几页", 而 Bangumi 的 offset 是
     * {@code (page-1)*limit}. 要多备一页才够切出第 2 页 —— 传 2 的话, 本地只到第 2 页的末尾,
     * 切片偏移刚好落在边界上, 用户看到的是"第 2 页比第 1 页短一截".
     *
     * <p>为什么不该问第 1、2 页: 那两页的内容本地已经有了, 拉回来是几百行白写的 UPDATE.
     */
    @Test
    @DisplayName("第 2 页: 问的是第 3 页(多备一页), 第 1、2 页一次都不问")
    void secondPageAsksForThePageAfterIt() {
        when(animeRepository.searchByKeywordPattern(any()))
                .thenReturn(localRows(1, 40))
                .thenReturn(localRows(1, 60));
        when(bangumiApiClient.searchSubjects(eq(KW), anyInt(), anyInt()))
                .thenReturn(response(200, remoteWithAlias(41)));

        Map<String, Object> result = animeService.searchAnime(KW, 2, 20);

        verify(bangumiApiClient).searchSubjects(KW, 3, 20);
        verify(bangumiApiClient, never()).searchSubjects(eq(KW), eq(1), anyInt());
        verify(bangumiApiClient, never()).searchSubjects(eq(KW), eq(2), anyInt());
        verify(bangumiApiClient, times(1)).searchSubjects(anyString(), anyInt(), anyInt());

        assertThat(idsOf(listOf(result))).as("第 2 页是第 21..40 条").startsWith(21).hasSize(20);
        assertThat(result.get("page")).isEqualTo(2);
    }

    // ========== 问几次 ==========

    /**
     * 第 1 页本地一条都没有, 于是要备到第 2 页的额度: 恰好 2 次.
     *
     * <p>这条钉的是"多备一页"带来的请求数 —— 它是 2 不是 1, 也不是"一直拉到够为止".
     * 前者的代价是每页固定一次外部请求(换来的是 total 稳定, 见
     * {@link #reportsBangumiTotalNotTheLocalCount()}); 后者是发散弹.
     */
    @Test
    @DisplayName("第 1 页本地为空: 恰好 2 次回源(备够第 2 页的额度就停)")
    void firstPageFetchesExactlyTwoRemotePages() {
        when(animeRepository.searchByKeywordPattern(any()))
                .thenReturn(List.of())
                .thenReturn(localRows(1, 20))
                .thenReturn(localRows(1, 40));
        when(bangumiApiClient.searchSubjects(eq(KW), anyInt(), anyInt()))
                .thenReturn(response(200, remoteWithAlias(1)))
                .thenReturn(response(200, remoteWithAlias(21)));

        Map<String, Object> result = animeService.searchAnime(KW, 1, 20);

        verify(bangumiApiClient).searchSubjects(KW, 1, 20);
        verify(bangumiApiClient).searchSubjects(KW, 2, 20);
        verify(bangumiApiClient, times(2)).searchSubjects(anyString(), anyInt(), anyInt());
        assertThat(idsOf(listOf(result))).hasSize(20);
    }

    /**
     * 本地已经够这一页(甚至够下一页)了 —— 一次外网都不该走.
     *
     * <p>这是"懒回源"三个字的全部意思: 回源是补救, 不是每次搜索的固定动作. 少了这条,
     * 一个已经缓存好的库每次搜索仍然会打一次外部 API, 而线上那台机器有没有外网是另一回事.
     */
    @Test
    @DisplayName("本地够用: 一次外网都不走（懒回源不是每次都回源）")
    void doesNotTouchRemoteWhenLocalIsEnough() {
        when(animeRepository.searchByKeywordPattern(any())).thenReturn(localRows(1, 100));

        Map<String, Object> result = animeService.searchAnime(KW, 1, 20);

        verifyNoInteractions(bangumiApiClient);
        assertThat(listOf(result)).hasSize(20);
        assertThat(result.get("total")).as("没有远端总数时退回本地口径").isEqualTo(100);
    }

    // ========== 上限 ==========

    /**
     * page 从 URL 手填成 9999 时, 回源次数必须封顶, 而且不能抛异常.
     *
     * <p>没有上限的话, 一个公开 GET 会对 Bangumi 打出近万次请求 —— 既是被封 IP 的路子,
     * 也是对第三方的不礼貌使用. 封顶之后深页返回空列表: 搜索页的翻页控件按 total 算,
     * 用户正常点不到这一页; 真手填了, 拿到"空的第 9999 页"也比 500 好.
     *
     * <p>这里只调用一次: 调两次会让计数器与调用次数统计互相干扰, 断言就不指向同一个事实了.
     * 不抛异常这件事由"这一行能跑完"本身保证 —— 真抛了, 用例直接红.
     */
    @Test
    @DisplayName("page=9999: 回源封顶 5 次, 不抛异常, 返回空页")
    void deepPageIsCappedAndDegradesToEmpty() {
        AtomicInteger queries = new AtomicInteger();
        when(animeRepository.searchByKeywordPattern(any()))
                .thenAnswer(inv -> localRows(1, Math.min(queries.incrementAndGet() * 20, 120)));
        when(bangumiApiClient.searchSubjects(eq(KW), anyInt(), anyInt()))
                .thenReturn(response(9999, remoteWithAlias(1)));

        Map<String, Object> result = animeService.searchAnime(KW, 9999, 20);

        verify(bangumiApiClient, times(5)).searchSubjects(anyString(), anyInt(), anyInt());
        assertThat(listOf(result)).isEmpty();
    }

    // ========== 报多少条 ==========

    /**
     * 报给前端的 total 是 Bangumi 的真实总数, 不是本地已有的条数.
     *
     * <p>为什么非要报真实值: {@code Pagination.vue} 是 {@code v-if="totalPages > 1"} ——
     * 只报本地那 20 条的话, 搜一个别名(本地常常只有一两页)根本不会渲染翻页控件,
     * 第 2 页永远点不到. 而切片边界仍然只能用手上真实有的行数, 否则深页会
     * {@code subList} 越界抛 500: 这两个数在 {@code buildSearchResult} 里是分开的.
     */
    @Test
    @DisplayName("total 报 Bangumi 的真实总数(200), 哪怕本地只有 40 条")
    void reportsBangumiTotalNotTheLocalCount() {
        when(animeRepository.searchByKeywordPattern(any()))
                .thenReturn(localRows(1, 20))
                .thenReturn(localRows(1, 40));
        when(bangumiApiClient.searchSubjects(eq(KW), anyInt(), anyInt()))
                .thenReturn(response(200, remoteWithAlias(21)));

        Map<String, Object> result = animeService.searchAnime(KW, 1, 20);

        assertThat(result.get("total")).isEqualTo(200);
        assertThat(listOf(result)).as("报 200 条不等于手上会多出 200 行").hasSize(20);
    }

    /**
     * 报的 total 不得小于手上真实有的行数.
     *
     * <p>远端总数与本地行数是两次独立查询的结果, 中间数据可能变了(有人删了条目、
     * 或者回源拿到的 total 是另一个口径). 报小了会让用户看不到自己**已经看到过**的那几页.
     */
    @Test
    @DisplayName("远端总数比本地行数还小时, 报本地行数(报小了会让用户退不回去)")
    void neverReportsLessThanWhatItHas() {
        when(animeRepository.searchByKeywordPattern(any()))
                .thenReturn(localRows(1, 20))
                .thenReturn(localRows(1, 60));
        when(bangumiApiClient.searchSubjects(eq(KW), anyInt(), anyInt()))
                .thenReturn(response(25, remoteWithAlias(21)));

        Map<String, Object> result = animeService.searchAnime(KW, 1, 20);

        assertThat(result.get("total")).isEqualTo(60);
    }

    // ========== 翻到头 / 回源失败 ==========

    /**
     * 远端返回空: 这是"翻到头了", 不是错误 —— 停手, 不再往下问.
     *
     * <p>搜一个冷门词时这是常态. 若把空响应当成失败继续问, 就到了上限才停;
     * 若当成异常抛出去, 一次正常的"没搜到"会变成 500.
     */
    @Test
    @DisplayName("远端返回空: 立刻停手(只问 1 次), 返回空页而不是报错")
    void emptyRemoteResponseStopsImmediately() {
        when(animeRepository.searchByKeywordPattern(any())).thenReturn(List.of());
        when(bangumiApiClient.searchSubjects(eq(KW), anyInt(), anyInt()))
                .thenReturn(response(0));

        Map<String, Object> result = animeService.searchAnime(KW, 1, 20);

        assertThat(listOf(result)).isEmpty();
        assertThat(result.get("total")).isEqualTo(0);
        verify(bangumiApiClient, times(1)).searchSubjects(anyString(), anyInt(), anyInt());
    }

    /**
     * 回源拿不到 total(响应里没有这个字段)时, 退回本地口径, 而不是报 null.
     *
     * <p>报 null 会让前端的 {@code Math.ceil(null/20)} 算出 NaN, 翻页控件整个不渲染 ——
     * 与"报 0"是同一种坏, 只是一个更难看.
     */
    @Test
    @DisplayName("回源响应没有 total 字段: 退回本地条数, 不报 null")
    void fallsBackToLocalCountWhenRemoteTotalIsMissing() {
        when(animeRepository.searchByKeywordPattern(any()))
                .thenReturn(List.of())
                .thenReturn(localRows(1, 20));
        when(bangumiApiClient.searchSubjects(eq(KW), anyInt(), anyInt()))
                .thenReturn(response(null, remoteWithAlias(1)));

        Map<String, Object> result = animeService.searchAnime(KW, 1, 20);

        assertThat(result.get("total")).isEqualTo(20);
    }

    // ========== 请求本身 ==========

    /**
     * 回源请求带的是**修过空白的用户关键词**, 拿回来的别名写进了实体.
     *
     * <p>两个都容易写错且都不会报错: 参数传错位置会拿回一堆无关的番(界面看起来"有结果"),
     * 别名没写进去则下次搜索原地踏步.
     */
    @Test
    @DisplayName("回源请求带的是用户的关键词; 拿回来的别名写进了实体")
    void sendsKeywordAndStoresAliases() {
        when(animeRepository.searchByKeywordPattern(any()))
                .thenReturn(List.of())
                .thenReturn(localRows(1, 20));
        when(bangumiApiClient.searchSubjects(anyString(), anyInt(), anyInt()))
                .thenReturn(response(200, remoteWithAlias(265, "EVA", "天鹰战士")))
                .thenReturn(response(200));

        animeService.searchAnime("  " + KW + "  ", 1, 20);

        // 这里会有两次回源: 第 2 次是"多备一页"那次, 远端回空于是停手. 断言的是**每一次**
        // 都带同一个修过空白的词 —— 只断第一次的话, 后面那次传错参数就漏过去了.
        ArgumentCaptor<String> sent = ArgumentCaptor.forClass(String.class);
        verify(bangumiApiClient, atLeastOnce()).searchSubjects(sent.capture(), anyInt(), anyInt());
        assertThat(sent.getAllValues()).as("关键词两端的空白要修掉").containsOnly(KW);

        assertThat(savedRows).as("远端空响应不该再落一次同样的行").hasSize(1);
        assertThat(savedRows.get(0).getAliases()).isEqualTo("EVA,天鹰战士");
    }
}
