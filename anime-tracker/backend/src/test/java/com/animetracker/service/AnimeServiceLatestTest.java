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
import org.mockito.ArgumentCaptor;
import org.springframework.cache.CacheManager;
import org.springframework.cache.concurrent.ConcurrentMapCacheManager;

import java.time.LocalDate;
import java.time.YearMonth;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.timeout;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * 「最近更新」的回源是不是真的挪到了别的线程上.
 *
 * <p><b>为什么这件事要单独验.</b> 改动前这条路上的四个 {@code Thread.sleep(500)}
 * 是在<b>请求线程</b>上睡的 —— 数据陈旧时一个用户"看一眼首页"要被阻塞两秒以上,
 * 而那两秒里它什么都没等到(它要的那一页数据手上就有). 而"挪到后台"这件事在返回值上
 * <b>完全看不出来</b>: 同步版与异步版返回的列表一模一样, 差别只在调用方等了多久.
 * 所以下面断言的不是返回了什么, 而是「回源发生在哪个线程上」「调用方有没有等它」.
 *
 * <p>不启 Spring 是刻意的(理由同 {@code AnimeServicePagingTest}): 这里要验的是几行
 * 线程调度, 不该顺带连库、跑启动预加载(它会联网).
 */
class AnimeServiceLatestTest {

    private static final int OLD_ID = 94100001;

    private AnimeRepository animeRepository;
    private BangumiApiClient apiClient;
    private CacheManager cacheManager;
    private AnimeService animeService;

    @BeforeEach
    void setUp() {
        animeRepository = mock(AnimeRepository.class);
        apiClient = mock(BangumiApiClient.class);
        // 名单与 CacheConfig 一致: ConcurrentMapCacheManager 传了名字之后就不再有
        // "按需创建", 名单外的名字取不到 —— 与生产同一套语义(见 CacheConfigTest)
        cacheManager = new ConcurrentMapCacheManager("ranking", "latest", "tags", "calendar");
        animeService = new AnimeService(
                animeRepository,
                mock(EpisodeRepository.class),
                mock(TagRepository.class),
                mock(AnimeTagRepository.class),
                apiClient,
                mock(BangumiApiProperties.class),
                new RankingProperties(),
                cacheManager);
    }

    private static Anime anime(int id, String date) {
        return Anime.builder().id(id).title("番" + id).date(date).build();
    }

    /** 库里的第一行是三年前的 —— needsRefresh 会判定为"旧" */
    private void givenTopRowIsOld() {
        when(animeRepository.findLatest(any(), any()))
                .thenReturn(List.of(anime(OLD_ID, YearMonth.now().minusYears(3) + "-01")));
    }

    // ========== 挪到后台 ==========

    /**
     * 这一条是整个改动存在的理由: 调用方不能再等回源.
     *
     * <p>断言手法是<b>线程名</b>而不是耗时 —— "两秒内返回"这种计时断言在负载高时
     * 会随机变红, 而"这次调用发生在 latest-refresh 线程上"是确定的: 如果回源仍在
     * 请求线程上跑, 这里拿到的就是 JUnit 的线程名.
     */
    @Test
    @DisplayName("数据陈旧: 立刻返回手上这批, 回源发生在 latest-refresh 线程上")
    void staleDataReturnsImmediatelyAndRefreshesOnAnotherThread() throws Exception {
        givenTopRowIsOld();
        CountDownLatch called = new CountDownLatch(1);
        AtomicReference<String> thread = new AtomicReference<>();
        when(apiClient.searchSubjects(anyString(), anyInt(), anyInt())).thenAnswer(inv -> {
            thread.set(Thread.currentThread().getName());
            called.countDown();
            // 回源拿不到东西(或对端不通)不影响"它在别的线程上"这个断言
            return null;
        });

        List<Anime> returned = animeService.getLatest(12);

        assertThat(returned).extracting(Anime::getId)
                .as("不等回源, 直接给手上这批")
                .containsExactly(OLD_ID);
        assertThat(called.await(2, TimeUnit.SECONDS)).as("回源应该已经开始").isTrue();
        assertThat(thread.get())
                .as("在请求线程上睡 4 次 500ms 就是改动前那个行为")
                .isEqualTo("latest-refresh");
    }

    /** 手上这批够新时一次 API 都不碰 —— 这条路上联网是被"日期旧"触发的, 不是每次 */
    @Test
    @DisplayName("第一行是近期的: 不回源, 一次 API 都不碰")
    void freshDataDoesNotTouchTheApi() {
        when(animeRepository.findLatest(any(), any()))
                .thenReturn(List.of(anime(OLD_ID + 1, YearMonth.now() + "-01")));

        assertThat(animeService.getLatest(12)).hasSize(1);

        verifyNoInteractions(apiClient);
    }

    @Test
    @DisplayName("limit<=0: 空列表, 且不回源")
    void nonPositiveLimitTouchesNothing() {
        assertThat(animeService.getLatest(0)).isEmpty();
        assertThat(animeService.getLatest(-1)).isEmpty();

        verifyNoInteractions(apiClient, animeRepository);
    }

    // ========== 异步化不引入新问题的两个必要条件 ==========

    /**
     * 已经有一次在跑时不能再排队.
     *
     * <p>没有这一条的话, 缓存过期之后的一串并发请求会各自排一次队, 而每次回源是
     * 四个请求 + 两秒睡眠 —— 队列比不做异步还糟. 这里用一个闩把第一次回源卡住,
     * 于是"在跑"这个状态是确定的, 不用靠计时去撞.
     */
    @Test
    @DisplayName("回源进行中: 后面的请求不会再排一次队")
    void inFlightRefreshIsNotQueuedAgain() throws Exception {
        givenTopRowIsOld();
        CountDownLatch parked = new CountDownLatch(1);
        when(apiClient.searchSubjects(anyString(), anyInt(), anyInt())).thenAnswer(inv -> {
            parked.await(5, TimeUnit.SECONDS);
            return null;
        });

        animeService.getLatest(12);
        animeService.getLatest(12);
        animeService.getLatest(12);

        try {
            verify(apiClient, timeout(2000).times(1))
                    .searchSubjects(anyString(), anyInt(), anyInt());
        } finally {
            parked.countDown();
        }
    }

    /**
     * 回源拿到新行之后必须清掉榜单类缓存.
     *
     * <p>不清的话这次请求缓存住的那份旧数据要等 TTL 到点才换掉 —— 那就等于把改动前
     * "用户至少能看见新数据"这个性质弄丢了(改动前是阻塞两秒换一次新鲜).
     *
     * <p>这条要等后台那四个 sleep(500) 跑完(清缓存放在最后, 理由见实现), 所以它是
     * 这一组里唯一会真的等两秒的用例.
     */
    @Test
    @DisplayName("回源结束后清掉榜单类缓存, 但不动 calendar")
    void refreshEvictsCatalogCachesButNotCalendar() throws Exception {
        givenTopRowIsOld();
        when(apiClient.searchSubjects(anyString(), anyInt(), anyInt())).thenReturn(null);
        cacheManager.getCache("latest").put(12, List.of(anime(OLD_ID, "2019-01-01")));
        cacheManager.getCache("ranking").put("rank_12", List.of(anime(OLD_ID, "2019-01-01")));
        cacheManager.getCache("calendar").put("today", List.of(anime(OLD_ID, "2019-01-01")));

        animeService.getLatest(12);

        awaitEvicted(cacheManager, "latest", 12);
        assertThat(cacheManager.getCache("ranking").get("rank_12")).isNull();
        assertThat(cacheManager.getCache("calendar").get("today"))
                .as("calendar 整份来自 Bangumi 的每日放送接口, 与本地多了几行无关")
                .isNotNull();
    }

    /**
     * 冷却期内不重复回源.
     *
     * <p>没有冷却会有两条自激回路: 对端不通时每个请求都再排一次(改动前那个失败结果
     * 会被 {@code @Cacheable} 缓存住, 反而不会重复试); 回源成功但数据仍然判定为旧时
     * "清缓存 → 未命中 → 又回源". 这里让它跑完一轮, 然后再问几次, 总数应该还是那一轮.
     */
    @Test
    @DisplayName("一轮回源之后再问几次: 不会再发第二轮")
    void cooldownStopsASecondRound() throws Exception {
        givenTopRowIsOld();
        when(apiClient.searchSubjects(anyString(), anyInt(), anyInt())).thenReturn(null);
        cacheManager.getCache("latest").put(12, List.of(anime(OLD_ID, "2019-01-01")));

        animeService.getLatest(12);
        // 等整轮跑完再问 —— "清缓存"是那一轮的最后一步, 它做完就意味着"在跑"那个
        // 标志已经放开. 不等的话, 下面两次会被"已经有一次在跑"挡住, 于是这条用例
        // 验的就成了 inFlight, 而不是它想验的冷却(两者都对, 但只有一个是它的题目).
        awaitEvicted(cacheManager, "latest", 12);
        verify(apiClient, times(4)).searchSubjects(anyString(), anyInt(), anyInt());

        animeService.getLatest(12);
        animeService.getLatest(12);

        verify(apiClient, times(4)).searchSubjects(anyString(), anyInt(), anyInt());
    }

    // ========== 「排除未来」那一改的另一半 ==========

    /**
     * 传给仓储的 {@code today} 必须是 {@code LocalDate.now().toString()} 那个形状.
     *
     * <p>这条看着琐碎, 守的却是一个**静默失效**: 谓词是 {@code a.date <= :today},
     * 两边都是字符串 —— 换成 {@code 2026/09/30} 或 {@code 20260930} 之后 SQL 照样跑,
     * 接口照样 200, 只是比较结果全错, 而"最近更新少了几部 / 多出几部未来番"
     * 没有任何人会立刻看出来. 查询本身的正确性由
     * {@code AnimeLatestIntegrationTest} 钉, 这里只钉格式.
     */
    @Test
    @DisplayName("传给仓储的 today 是 ISO 的 yyyy-MM-dd")
    void passesAnIsoDateAsToday() {
        when(animeRepository.findLatest(any(), any()))
                .thenReturn(List.of(anime(OLD_ID + 1, YearMonth.now() + "-01")));

        animeService.getLatest(12);

        ArgumentCaptor<String> today = ArgumentCaptor.forClass(String.class);
        verify(animeRepository, atLeastOnce()).findLatest(today.capture(), any());
        assertThat(today.getAllValues()).isNotEmpty();
        for (String value : today.getAllValues()) {
            assertThat(value).as("形状错了整条谓词就静默错").matches("\\d{4}-\\d{2}-\\d{2}");
            assertThat(LocalDate.parse(value))
                    .as("跨零点的那一瞬可能与断言时的今天差一天")
                    .isBetween(LocalDate.now().minusDays(1), LocalDate.now());
        }
    }

    /** 等后台把清缓存那一步做完 —— 它在四个 sleep(500) 之后 */
    private static void awaitEvicted(CacheManager manager, String name, Object key)
            throws InterruptedException {
        long deadline = System.currentTimeMillis() + 5000;
        while (manager.getCache(name).get(key) != null && System.currentTimeMillis() < deadline) {
            Thread.sleep(50);
        }
    }
}
