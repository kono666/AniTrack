package com.animetracker.service;

import com.animetracker.config.BangumiApiProperties;
import com.animetracker.config.RankingProperties;
import com.animetracker.dto.BangumiDTO.CalendarDay;
import com.animetracker.dto.BangumiDTO.CalendarItem;
import com.animetracker.entity.Anime;
import com.animetracker.repository.AnimeRepository;
import com.animetracker.repository.AnimeTagRepository;
import com.animetracker.repository.EpisodeRepository;
import com.animetracker.repository.TagRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.cache.concurrent.ConcurrentMapCacheManager;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.after;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * 日历入库是不是真的在别的线程上, 以及它挪走之后有没有引入新问题.
 *
 * <p><b>为什么这件事要单独验.</b> 改动前 getCalendar() 的两层 for 就写在请求线程上,
 * 而它上面那句注释("异步缓存日历中的动漫")是假的 —— 实测缓存未命中要 6.7 秒、命中 6 毫秒.
 * 而"挪到后台"在<b>返回值上完全看不出来</b>: 同步版与异步版返回的是同一个列表, 差别只在
 * 调用方等了多久. 所以下面断言的不是返回了什么, 而是「入库发生在哪个线程上」「调用方有没有
 * 等它」, 以及异步化之后必须仍然成立的两件事(不重复排队、单条失败不连坐).
 *
 * <p>不启 Spring 是刻意的(理由同 {@code AnimeServiceLatestTest}): 要验的是几行线程调度,
 * 不该顺带连库、跑启动预加载. 代价是 {@code @Cacheable} 在这里不生效(没有代理), 所以
 * 每次调用都会走到调度那一步 —— 这恰好是下面「不重复排队」那条要利用的性质.
 */
class AnimeServiceCalendarTest {

    private static final int ID_A = 94200001;
    private static final int ID_B = 94200002;
    private static final int ID_C = 94200003;

    private AnimeRepository animeRepository;
    private BangumiApiClient apiClient;
    private AnimeService animeService;

    @BeforeEach
    void setUp() {
        animeRepository = mock(AnimeRepository.class);
        apiClient = mock(BangumiApiClient.class);
        animeService = new AnimeService(
                animeRepository,
                mock(EpisodeRepository.class),
                mock(TagRepository.class),
                mock(AnimeTagRepository.class),
                apiClient,
                mock(BangumiApiProperties.class),
                new RankingProperties(),
                // 名单与 CacheConfig 一致, 见 CacheConfigTest
                new ConcurrentMapCacheManager("ranking", "latest", "tags", "calendar"));
    }

    private static CalendarItem item(int id) {
        CalendarItem it = new CalendarItem();
        it.setId(id);
        it.setName("番" + id);
        return it;
    }

    private static CalendarDay day(CalendarItem... items) {
        CalendarDay d = new CalendarDay();
        d.setWeekday(Map.of("cn", "周一"));
        d.setItems(List.of(items));
        return d;
    }

    /** 每个条目一次 findById 加一次 save(见 upsertCalendarItem)。给 save 挂个闩就知道整批跑完没有 */
    private CountDownLatch countingSaves(int expected) {
        CountDownLatch saved = new CountDownLatch(expected);
        when(animeRepository.save(any(Anime.class))).thenAnswer(inv -> {
            saved.countDown();
            return inv.getArgument(0);
        });
        return saved;
    }

    // ========== 挪到后台 ==========

    /**
     * 这一条是整个改动存在的理由: 调用方不能再等那 112 次 upsert.
     *
     * <p>断言手法是<b>线程名</b>而不是耗时 —— "几百毫秒内返回"这种计时断言在负载高时会随机
     * 变红, 而"这次入库发生在 calendar-cache 线程上"是确定的: 如果入库仍在请求线程上跑,
     * 这里拿到的就是 JUnit 的线程名.
     */
    @Test
    @DisplayName("入库发生在 calendar-cache 线程上, 调用方不等它")
    void upsertsRunOffTheRequestThread() throws Exception {
        when(apiClient.getCalendar()).thenReturn(List.of(day(item(ID_A), item(ID_B))));
        CountDownLatch saved = countingSaves(2);
        AtomicReference<String> thread = new AtomicReference<>();
        when(animeRepository.findById(anyInt())).thenAnswer(inv -> {
            thread.set(Thread.currentThread().getName());
            return Optional.empty();
        });

        List<CalendarDay> returned = animeService.getCalendar();

        // 返回的是刚拿到手的那份, 一条都不来自库里 —— 也就是说用户等那几秒, 等的是一件
        // 与他的响应无关的事. 这句断言是"把它挪走不损失任何东西"的依据.
        assertThat(returned).hasSize(1);
        assertThat(returned.get(0).getItems()).extracting(CalendarItem::getId)
                .containsExactly(ID_A, ID_B);

        assertThat(saved.await(2, TimeUnit.SECONDS)).as("入库应该已经在后台跑起来了").isTrue();
        assertThat(thread.get())
                .as("在请求线程上做 112 次 upsert 就是改动前那个行为(实测 6.7 秒)")
                .isEqualTo("calendar-cache");
    }

    /** 日历取空时不该白排一次队 —— 空列表进了后台也就是立刻空转一圈 */
    @Test
    @DisplayName("日历取空: 不排入库")
    void emptyCalendarSchedulesNothing() {
        when(apiClient.getCalendar()).thenReturn(List.of());

        assertThat(animeService.getCalendar()).isEmpty();

        verifyNoInteractions(animeRepository);
    }

    // ========== 异步化不引入新问题的两个必要条件 ==========

    /**
     * 已经有一批在跑时不能再排队.
     *
     * <p>没有这一条的话, 缓存过期那一瞬间的并发请求会各排一次队(见 {@code @Cacheable} 与
     * 这里的调度之间那道缝), 而每批是一次一百多条 upsert —— 队列比不做异步还糟.
     *
     * <p>断言用 Mockito 的 after() 而不是 timeout(): timeout() 在**看到**第 1 次调用时就
     * 返回了, 后面再来几次它不管; 这里要的恰恰是"过一会儿再看, 仍然只有 1 次".
     * 为了让 1 这个数字说得通, 假数据刻意只有**一个**条目 —— 一批就是一次 findById.
     */
    @Test
    @DisplayName("入库进行中: 后面的请求不会再排一次队")
    void inFlightBatchIsNotQueuedAgain() throws Exception {
        when(apiClient.getCalendar()).thenReturn(List.of(day(item(ID_A))));
        CountDownLatch parked = new CountDownLatch(1);
        when(animeRepository.findById(anyInt())).thenAnswer(inv -> {
            parked.await(5, TimeUnit.SECONDS);
            return Optional.empty();
        });

        // 三连问: 若没有那道闸, 三个批次会一起排进队列
        animeService.getCalendar();
        animeService.getCalendar();
        animeService.getCalendar();

        parked.countDown();

        verify(animeRepository, after(500).times(1)).findById(anyInt());
    }

    /**
     * 单条脏数据不能把后面一百多条一起挡在门外.
     *
     * <p>这是一处与改动前**必须一致**的行为. 写在这里是因为它和"挪后台"有一条隐含的冲突:
     * 把这批并入一个事务是减少提交次数的自然写法, 而一旦并了, 坏的那条会把整批回滚 ——
     * 下面这个 catch 就不再成立了. 所以这条用例守的不只是容错, 还守着"别顺手合并事务".
     */
    @Test
    @DisplayName("某一条 upsert 抛异常: 只丢它自己, 后面的照常入库")
    void oneBadItemDoesNotBlockTheRest() throws Exception {
        when(apiClient.getCalendar())
                .thenReturn(List.of(day(item(ID_A), item(ID_B), item(ID_C))));
        CountDownLatch saved = countingSaves(2);
        when(animeRepository.findById(anyInt())).thenAnswer(inv -> {
            if (ID_B == (Integer) inv.getArgument(0)) {
                throw new IllegalStateException("脏条目");
            }
            return Optional.empty();
        });

        animeService.getCalendar();

        assertThat(saved.await(2, TimeUnit.SECONDS))
                .as("坏的排中间: 它前面那条与它后面那条都该进得去").isTrue();
        // 三条里坏一条, 所以正好两次. 不用 after(): 上面那个闩已经说明这批次走完了,
        // 而且坏条目在 for 里是被 catch 吞掉的, 不会有第五次调用在路上.
        verify(animeRepository, times(2)).save(any(Anime.class));
    }
}
