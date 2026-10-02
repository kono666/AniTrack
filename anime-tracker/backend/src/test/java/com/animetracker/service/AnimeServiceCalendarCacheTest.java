package com.animetracker.service;

import com.animetracker.config.BangumiApiProperties;
import com.animetracker.config.RankingProperties;
import com.animetracker.dto.BangumiDTO.CalendarDay;
import com.animetracker.dto.BangumiDTO.CalendarItem;
import com.animetracker.repository.AnimeRepository;
import com.animetracker.repository.AnimeTagRepository;
import com.animetracker.repository.EpisodeRepository;
import com.animetracker.repository.TagRepository;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.cache.CacheManager;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.cache.annotation.EnableCaching;
import org.springframework.aop.support.AopUtils;
import org.springframework.cache.concurrent.ConcurrentMapCacheManager;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.context.annotation.Configuration;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * calendar 这条缓存的两处修正: <b>空结果不进缓存</b>({@code unless}), 以及 key 里装的是
 * 整周而不是当天.
 *
 * <p><b>为什么必须新开一个类, 不能加进 {@link AnimeServiceCalendarTest}.</b> 那个类是手工
 * {@code new AnimeService(...)} 的, <b>没有 Spring 代理, {@code @Cacheable} 完全不生效</b>
 * (它自己的类注释写着这件事, 并且正是靠这个性质验"不重复排队"). 在那种环境里加一条
 * "空结果不该被缓存"的用例, 无论 {@code unless} 写没写都会绿 —— 因为压根没有缓存参与,
 * 每次调用都老老实实打上游. <b>假绿比没测更糟</b>: 它让下一个人以为这里有人守着.
 * 所以这一组必须让**真的那个 {@code AnimeService} 实例**穿过容器一次, 代理才会生成.
 *
 * <p>用 {@link ApplicationContextRunner} 而不是 {@code @SpringBootTest}: 这里需要的只是一个
 * {@code @EnableCaching} 加一个 {@code CacheManager}, 不该为此连库、跑启动预加载
 * (那还会顺带带进数据初始化的副作用).
 *
 * <p>缓存名给四个而不是只给 {@code calendar}: 非空那条路径会把日历排进后台入库, 而入库
 * 路上会碰别的缓存. 少了名字取到 null 虽然只在后台线程里被 catch 掉, 但日志会脏.
 */
class AnimeServiceCalendarCacheTest {

    private AnimeRepository animeRepository;
    private BangumiApiClient apiClient;

    /** 一个"上游正常"的整周响应: 七天, 每天一条 */
    private static List<CalendarDay> sevenDays() {
        return List.of(
                day("1", "星期一"), day("2", "星期二"), day("3", "星期三"), day("4", "星期四"),
                day("5", "星期五"), day("6", "星期六"), day("7", "星期日"));
    }

    private static CalendarDay day(String id, String cn) {
        CalendarDay d = new CalendarDay();
        // 上游的 weekday 是 {en, cn, ja, id}, 而 DTO 这边声明成 Map<String,String>
        // (Jackson 把数字 id 强制成字符串) —— 照真实形状构造, 别用别的类型
        d.setWeekday(Map.of("en", "Mon", "cn", cn, "ja", "月曜日", "id", id));
        CalendarItem item = new CalendarItem();
        item.setId(9000 + Integer.parseInt(id));
        item.setNameCn("番 " + id);
        d.setItems(List.of(item));
        return d;
    }

    private AnnotationConfigApplicationContext ctx;
    private AnimeService service;

    @BeforeEach
    void setUp() {
        animeRepository = mock(AnimeRepository.class);
        apiClient = mock(BangumiApiClient.class);
        service = proxiedService();
    }

    /**
     * 容器必须活到这一条用例跑完.
     *
     * <p>改前这里用的是 {@code ApplicationContextRunner.run(...)} —— 它在 run 返回时就把
     * 上下文关了, 于是 {@code @PreDestroy} 立刻跑掉, 把 {@code calendarCacheExecutor}
     * shutdown 掉; 后面任何一次 {@code getCalendar()} 走非空分支都会抛
     * {@code RejectedExecutionException: ... [Terminated, pool size = 0]}. 那个错看着像
     * "非空分支坏了", 其实是**容器生命周期**的问题 —— 所以这里自己建上下文, 自己决定
     * 什么时候关。
     */
    @AfterEach
    void tearDown() {
        if (ctx != null) {
            ctx.close();
        }
    }

    /**
     * 只负责打开 {@code @EnableCaching}. CacheManager 从外面用 {@code withBean} 给。
     *
     * <p>刻意**不**在这里写 {@code @Bean CacheManager}: 那样容器里会有两个同类型的 bean,
     * 而 {@code @EnableCaching} 解析 CacheManager 时是按类型找的, 撞上两个就
     * {@code NoUniqueBeanDefinitionException} —— 与"缓存没生效"长得完全不同, 容易查错方向.
     */
    @Configuration
    @EnableCaching
    static class CachingConfig {
    }

    /**
     * 让 {@code AnimeService} 真的被容器创建一次(于是被缓存代理包住), 再交给测试用.
     *
     * <p>注册的是 BeanDefinition + 实例供给, 仍然走容器, 所以自动代理照常发生 ——
     * 这一点是这个类成立的前提。
     *
     * <p>CacheManager **只造一个实例**, 容器与 {@code AnimeService} 的构造参数共用它:
     * 两条路径读的必须是同一份缓存, 否则 {@code @Cacheable} 写进去的东西, service 内部
     * 那句 evict 看不见, 而"清不掉"的表现是"改了数据但页面还是旧的".
     *
     * <p>名字给四个而不是只给 {@code calendar}: 非空那条路径会把日历排进后台入库,
     * 入库路上会碰别的缓存. 名单固定(不是按需新建)因此也会在名字拼错时当场抛。
     */
    private AnimeService proxiedService() {
        CacheManager cacheManager =
                new ConcurrentMapCacheManager("ranking", "latest", "tags", "calendar");
        AnimeService real = new AnimeService(
                animeRepository,
                mock(EpisodeRepository.class),
                mock(TagRepository.class),
                mock(AnimeTagRepository.class),
                apiClient,
                mock(BangumiApiProperties.class),
                new RankingProperties(),
                cacheManager);

        ctx = new AnnotationConfigApplicationContext();
        ctx.register(CachingConfig.class);
        ctx.registerBean("cacheManager", CacheManager.class, () -> cacheManager);
        ctx.registerBean("animeService", AnimeService.class, () -> real);
        ctx.refresh();

        AnimeService proxied = ctx.getBean(AnimeService.class);
        // 这条是**这个类能不能算数的总闸**: 没有代理就没有缓存, 于是
        // emptyResultIsNotCached 会因为"每次都打上游"而假绿 —— 它想守的东西其实没被守.
        // 真出这件事时 nonEmptyResultIsCached 也会红(times(1) 收到 2), 这里先把原因写明。
        assertThat(AopUtils.isAopProxy(proxied))
                .as("AnimeService 没被缓存代理包住 —— @Cacheable 在这组断言里等于不存在")
                .isTrue();
        return proxied;
    }

    @Test
    @DisplayName("上游回空: 空列表不进缓存, 恢复之后立刻拿得到数据(不会空两小时)")
    void emptyResultIsNotCached() {
        when(apiClient.getCalendar()).thenReturn(List.of());

        assertThat(service.getCalendar()).isEmpty();
        // 关键: 第二次调用必须**再打一次上游**. 若 unless 掉了, 这里会命中那份空缓存,
        // 拿到同样一个空列表 —— 返回值一模一样, 只有调用次数能看出区别.
        assertThat(service.getCalendar()).isEmpty();

        verify(apiClient, times(2)).getCalendar();
    }

    @Test
    @DisplayName("对照: 上游回整周时照常缓存, 第二次不再打上游(证明 unless 不是恒真)")
    void nonEmptyResultIsCached() {
        when(apiClient.getCalendar()).thenReturn(sevenDays());

        assertThat(service.getCalendar()).hasSize(7);
        assertThat(service.getCalendar()).hasSize(7);

        // 反向验证的另一半: 把 unless 写成 "true"(恒真)会让这条红 —— 那正是
        // "防住了空结果但顺手把缓存整个关掉"这个错误的形状.
        verify(apiClient, times(1)).getCalendar();
    }

    @Test
    @DisplayName("上游抖一下(空)之后恢复: 那一次空没有把后面的真实数据挡住")
    void transientEmptyDoesNotShadowTheRecovery() {
        when(apiClient.getCalendar()).thenReturn(List.of());
        assertThat(service.getCalendar()).isEmpty();

        // 上游恢复
        when(apiClient.getCalendar()).thenReturn(sevenDays());

        // 改前这里是空列表 —— 用户看到的是"放送表整个没了", 而且要等 TTL 到期才回来
        assertThat(service.getCalendar())
                .as("一次瞬时故障不该被固化成一个产品状态")
                .hasSize(7);
    }

    @Test
    @DisplayName("缓存 key 是 'week' 不是 'today': 里面装的一直是整周七天")
    void cacheKeyNamesTheWholeWeek() throws Exception {
        Cacheable ann = AnimeService.class.getMethod("getCalendar").getAnnotation(Cacheable.class);
        assertThat(ann).as("getCalendar 上没有 @Cacheable 了?").isNotNull();
        // 改名前是 'today', 而返回值是七天 —— 下一个人会照着名字以为拿到的是当天
        assertThat(ann.key()).isEqualTo("'week'");
        assertThat(ann.value()).containsExactly("calendar");
        assertThat(ann.unless())
                .as("unless 空了就等于没有这道闸 —— 行为断言在上面三条里")
                .isNotBlank();
    }
}
