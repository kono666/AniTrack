package com.animetracker.service;

import com.animetracker.dto.BangumiDTO.SearchResponse;
import com.animetracker.dto.BangumiDTO.SubjectDTO;
import com.animetracker.repository.AnimeRepository;
import com.animetracker.service.AnimeBackfillService.BackfillResult;
import com.animetracker.service.AnimeBackfillService.StopReason;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 全量回填的翻页控制: 翻到第几页停、停的时候算不算跑完、下一次从哪接着跑.
 *
 * <p>这一组盯的是**循环的边界**, 不是落库 —— upsert 本身由
 * {@code AnimeFieldsIntegrationTest} 在真库上验. 边界才是这里唯一会出事的地方:
 * 一个少翻一页的回填, 结果看起来和跑完了一模一样(库里就是少了几千条, 没有任何地方报错).
 *
 * <p>所以这个类里最要紧的一条是 {@link #requestFailureIsNotTheEnd()}:
 * "请求失败"与"翻到底了"必须是两条路. 混成一条, 一次网络抖动就会让回填**安静地提前收工**.
 *
 * <p>不启 Spring: 这里要验的是十几行控制流, 不该顺带连库、跑启动预加载(它会联网).
 * 代价是 {@code @CacheEvict} 验不到(那是代理的行为, 没有容器就没有代理) —— 这一条
 * 只能靠"回填跑完之后排行榜确实变了"在手工验收里看.
 */
class AnimeBackfillServiceTest {

    private BangumiApiClient api;
    private AnimeRepository animeRepo;
    private AnimeService animeService;

    @BeforeEach
    void setUp() {
        api = mock(BangumiApiClient.class);
        animeRepo = mock(AnimeRepository.class);
        animeService = mock(AnimeService.class);
        when(animeRepo.count()).thenReturn(470L);
    }

    // ========== 造数据 ==========

    private static SubjectDTO subject(Integer id) {
        SubjectDTO dto = new SubjectDTO();
        dto.setId(id);
        dto.setName("s" + id);
        return dto;
    }

    /** 一页远端响应. ids 为空即"空页" —— 也就是"翻到底了"的信号 */
    private static SearchResponse page(Integer total, Integer... ids) {
        SearchResponse resp = new SearchResponse();
        resp.setTotal(total);
        List<SubjectDTO> data = new ArrayList<>();
        for (Integer id : ids) {
            data.add(subject(id));
        }
        resp.setData(data);
        return resp;
    }

    /** 默认参数: 从 0 开始, 每页 50, 不限页数, 不等待(测试里没必要真 sleep) */
    private AnimeBackfillService service() {
        return service(0, 50, 700, 0);
    }

    private AnimeBackfillService service(int startOffset, int pageSize, int maxPages, long delayMs) {
        return new AnimeBackfillService(api, animeRepo, animeService, startOffset, pageSize, maxPages, delayMs);
    }

    // ========== 正常翻到底 ==========

    /**
     * 一路翻到 Bangumi 报的 total 就停手, 不多问一次.
     *
     * <p>为什么"不多问一次"值得单独钉: 最后一页正好填满 total 时, 再问一页会拿到空页 ——
     * 结果没错, 但那是**多打一次别人的服务器**. 588 页的爬虫不该有这种尾巴.
     */
    @Test
    @DisplayName("翻到 total 就停: 第 2 页正好凑满 5 条 -> 不再问第 3 页")
    void stopsAtReportedTotal() {
        when(api.browseSubjects(0, 50)).thenReturn(page(5, 1, 2, 3));
        when(api.browseSubjects(3, 50)).thenReturn(page(5, 4, 5));
        when(animeRepo.count()).thenReturn(470L, 475L);

        BackfillResult r = service().backfill();

        assertThat(r.stopped()).isEqualTo(StopReason.END_OF_DATA);
        assertThat(r.complete()).isTrue();
        assertThat(r.pages()).isEqualTo(2);
        assertThat(r.fetched()).isEqualTo(5);
        assertThat(r.nextOffset()).isEqualTo(5);
        assertThat(r.reportedTotal()).isEqualTo(5);
        assertThat(r.countBefore()).isEqualTo(470);
        assertThat(r.countAfter()).isEqualTo(475);

        verify(api, times(2)).browseSubjects(anyInt(), anyInt());
        verify(animeService, times(5)).upsertAnime(any(SubjectDTO.class));
    }

    /**
     * 没有 total 的响应里, 空页就是终点 —— 而且这仍然算"跑完了".
     *
     * <p>空页是**数据本身**给出的终点, 比总数字段更可信(总数是另一个时刻拍的快照).
     * 所以"跑完了"这件事不能只看 total: 只看 total 的话, 服务端哪天不回这个字段,
     * 一次真正完整的回填会被判成"半路断了", 然后被人一遍遍重跑.
     */
    @Test
    @DisplayName("响应没有 total: 空页即终点, 依然算跑完")
    void emptyPageIsEndEvenWithoutTotal() {
        when(api.browseSubjects(0, 50)).thenReturn(page(null, 1, 2));
        when(api.browseSubjects(2, 50)).thenReturn(page(null));

        BackfillResult r = service().backfill();

        assertThat(r.stopped()).isEqualTo(StopReason.END_OF_DATA);
        assertThat(r.complete()).isTrue();
        assertThat(r.fetched()).isEqualTo(2);
        assertThat(r.nextOffset()).isEqualTo(2);
        verify(api, times(2)).browseSubjects(anyInt(), anyInt());
    }

    // ========== 这一组存在的理由 ==========

    /**
     * 请求失败**不是**翻到底 —— 必须停下并留下续跑点, 而不是当成终点安静收工.
     *
     * <p>这是这个类里最要紧的一条. 把 null 当成空页处理, 一次网络抖动就会让回填
     * **提前结束**, 而它的结果(`complete()` / 日志 / 库里的条数)看起来和"跑完了"
     * 完全一样. 一个少了六千条的库, 没有任何地方会报错 —— 直到有人搜一部老番搜不到.
     *
     * <p>所以断言三件事: 停因是 REQUEST_FAILED、不算跑完、**nextOffset 指向出事的那一页**
     * (下一次从它接着跑, 中间不丢一条).
     */
    @Test
    @DisplayName("请求失败: 停下、不算跑完、nextOffset 指向出事的那一页")
    void requestFailureIsNotTheEnd() {
        when(api.browseSubjects(0, 50)).thenReturn(page(100, 1, 2));
        when(api.browseSubjects(2, 50)).thenReturn(null);   // 网络抖了一下

        BackfillResult r = service().backfill();

        assertThat(r.stopped()).isEqualTo(StopReason.REQUEST_FAILED);
        assertThat(r.complete()).as("还有 98 条没拿到, 不能算跑完").isFalse();
        assertThat(r.nextOffset()).as("续跑点").isEqualTo(2);
        assertThat(r.fetched()).isEqualTo(2);
        assertThat(r.reportedTotal()).isEqualTo(100);

        verify(api, times(2)).browseSubjects(anyInt(), anyInt());
        verify(animeService, times(2)).upsertAnime(any(SubjectDTO.class));
    }

    /**
     * 响应体是 null(拿到了 200 但没内容)与 data 是 null, 都按"请求没成"处理.
     *
     * <p>两者都会 `resp.getData()` 直接 NPE —— 而 NPE 会一路冒到调用方, 把"这一页没拿到"
     * 升级成"整个回填挂了". 停下来、记住续跑点, 才是这一层该做的事.
     */
    @Test
    @DisplayName("响应体为 null: 按请求失败处理, 不抛 NPE")
    void nullResponseBodyIsAFailure() {
        when(api.browseSubjects(0, 50)).thenReturn(new SearchResponse());   // data 为 null

        BackfillResult r = service().backfill();

        assertThat(r.stopped()).isEqualTo(StopReason.REQUEST_FAILED);
        assertThat(r.fetched()).isZero();
        assertThat(r.nextOffset()).isZero();
    }

    // ========== 翻页的步子 ==========

    /**
     * 下一页的 offset 按**实际返回的条数**推进, 不按 pageSize.
     *
     * <p>服务端返回一页短的(它自己有过滤、或者我们这边跳过了一些行)时, 按 pageSize 推进
     * 会**跳过**那段没返回的区间 —— 跳得无声无息, 和"失败当成终点"是同一类错:
     * 库里少一截, 外面看不出来. 这条用例给一页 7 条(请求的是 50), 下一问必须是 offset=7.
     */
    @Test
    @DisplayName("某一页只有 7 条(请求的是 50): 下一问是 offset=7, 不是 50")
    void advancesByActualPageLength() {
        when(api.browseSubjects(0, 50)).thenReturn(page(10, 1, 2, 3, 4, 5, 6, 7));
        when(api.browseSubjects(7, 50)).thenReturn(page(10, 8, 9, 10));

        BackfillResult r = service().backfill();

        verify(api).browseSubjects(7, 50);
        verify(api, never()).browseSubjects(50, 50);
        assertThat(r.complete()).isTrue();
        assertThat(r.nextOffset()).isEqualTo(10);
    }

    /**
     * start-offset 生效: 断了之后接着跑, 而不是从头把三万个条目再翻一遍.
     *
     * <p>第一次请求就必须落在 start-offset 上. 写错成 0 不会有任何报错 —— 只是白跑几小时,
     * 而且是**重写库里已有的那些行**, 比白跑更贵.
     */
    @Test
    @DisplayName("start-offset=29300: 第一次请求就是 offset=29300")
    void startsFromConfiguredOffset() {
        when(api.browseSubjects(29300, 50)).thenReturn(page(29378, 29301, 29302));
        when(api.browseSubjects(29302, 50)).thenReturn(page(29378));

        BackfillResult r = service(29300, 50, 700, 0).backfill();

        verify(api).browseSubjects(29300, 50);
        verify(api, never()).browseSubjects(0, 50);
        assertThat(r.complete()).isTrue();
        assertThat(r.nextOffset()).isEqualTo(29302);
    }

    /**
     * max-pages 是刹车, 踩下去要报 MAX_PAGES, 不能报"跑完了".
     *
     * <p>这个上限的意义是"别无限跑下去"(配置写错、或对端 total 突然变得很大时的兜底).
     * 它停下时后面明明还有数据, 所以不计入 complete —— 否则一个只跑了两页的回填
     * 会被当成完成, 那正是这个开关最坏的用法.
     */
    @Test
    @DisplayName("max-pages=2: 翻 2 页就停, 报 MAX_PAGES, 不算跑完")
    void maxPagesIsABrakeNotAnEnd() {
        when(api.browseSubjects(anyInt(), anyInt())).thenReturn(page(1000, 1, 2, 3));

        BackfillResult r = service(0, 50, 2, 0).backfill();

        assertThat(r.stopped()).isEqualTo(StopReason.MAX_PAGES);
        assertThat(r.complete()).isFalse();
        assertThat(r.pages()).isEqualTo(2);
        assertThat(r.nextOffset()).as("续跑点").isEqualTo(6);
        verify(api, times(2)).browseSubjects(anyInt(), anyInt());
    }

    // ========== 落库的那一步 ==========

    /**
     * 没有 id 的行不落库, 但**照样占一个 offset**.
     *
     * <p>两件事得同时成立, 而且方向相反: anime 的主键就是 Bangumi 的 subject_id,
     * 没有 id 的行落下去没有意义; 但 offset 是**对端的页码口径**, 少算一条就会
     * 把它后面那条错位成前一条 —— 后面几万条会集体错位一格.
     *
     * <p>顺带钉住"抓取条数"的口径: 报的是**真正落库的条数**(2), 不是这一页的原始长度(4).
     * 报原始长度的话, 日志里"抓取 29378 条"和"库内 29378 条"会各说各话, 而没人知道
     * 差在哪.
     */
    @Test
    @DisplayName("缺 id 的行/null 元素: 不落库(报 2 条), 但仍占 offset(下一问是 4)")
    void skipsRowsWithoutIdButStillCountsThemInTheOffset() {
        SubjectDTO noId = new SubjectDTO();
        noId.setName("没有 id");
        SearchResponse resp = new SearchResponse();
        resp.setTotal(100);   // 故意远大于这一页: 让"翻到底"的短路不参与, 单独看 offset 怎么走
        resp.setData(new ArrayList<>(Arrays.asList(subject(1), null, noId, subject(2))));
        when(api.browseSubjects(0, 50)).thenReturn(resp);
        when(api.browseSubjects(4, 50)).thenReturn(null);   // 第二页没拿到, 就此打住

        BackfillResult r = service().backfill();

        verify(api).browseSubjects(4, 50);
        verify(api, never()).browseSubjects(2, 50);
        verify(api, never()).browseSubjects(50, 50);
        verify(animeService, times(2)).upsertAnime(any(SubjectDTO.class));
        assertThat(r.fetched()).as("报落库的 2 条, 不是这一页的 4 条").isEqualTo(2);
        assertThat(r.nextOffset()).as("offset 走的是对端的页码口径").isEqualTo(4);
    }

    /**
     * 空的第一页: 一条都不落库, 一次 upsert 都不调.
     *
     * <p>库已经同步过一次之后再跑, 这是正常结局(而不是异常). 它同时是一道保险:
     * 万一 limit 被配成 0 之类, 这里不会变成一个疯狂重试的死循环.
     */
    @Test
    @DisplayName("第一页就是空的: 0 次 upsert, 立刻收工")
    void emptyFirstPageDoesNothing() {
        when(api.browseSubjects(0, 50)).thenReturn(page(0));

        BackfillResult r = service().backfill();

        assertThat(r.stopped()).isEqualTo(StopReason.END_OF_DATA);
        assertThat(r.pages()).isZero();
        verify(animeService, never()).upsertAnime(any(SubjectDTO.class));
    }

    // ========== 关停 ==========

    /**
     * 线程被中断(应用关停)时停在页边界上, 并报出续跑点.
     *
     * <p>这里刻意用 delay-ms=1000 让 sleep 真的被调用 —— delay-ms=0 的分支根本不 sleep,
     * 也就永远走不到 {@code InterruptedException} 那一段. 中断位是**预先置上**的,
     * sleep 会立刻抛出而不是真等一秒.
     *
     * <p>要恢复中断位这件事没在这里断言(它是 sleepBetweenRequests 内部的一行),
     * 但清掉它是必须的: 漏给后面的用例, 会让它们在一个"已被中断"的线程上跑.
     */
    @Test
    @DisplayName("线程被中断: 停在页边界, 报 INTERRUPTED 与续跑点, 不抛异常")
    void interruptedCrawlStopsAtAPageBoundary() {
        when(api.browseSubjects(0, 50)).thenReturn(page(1000, 1, 2, 3));

        Thread.currentThread().interrupt();
        try {
            BackfillResult r = service(0, 50, 700, 1000).backfill();

            assertThat(r.stopped()).isEqualTo(StopReason.INTERRUPTED);
            assertThat(r.complete()).isFalse();
            assertThat(r.pages()).as("这一页是落完库才睡的, 所以算数").isEqualTo(1);
            assertThat(r.nextOffset()).isEqualTo(3);
            verify(animeService, times(3)).upsertAnime(any(SubjectDTO.class));
        } finally {
            Thread.interrupted();   // 清掉中断位
        }
    }
}
