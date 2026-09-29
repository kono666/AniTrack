package com.animetracker.config;

import com.animetracker.dto.BangumiDTO.*;
import com.animetracker.repository.AnimeRepository;
import com.animetracker.service.AnimeService;
import com.animetracker.service.BangumiApiClient;
import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

import java.time.Year;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * 启动后静默从 Bangumi 拉取动漫数据到本地缓存。
 * 策略：用常见搜索词分批搜索，积累足够数据后首页即可走本地。
 *
 * <p>落库统一走 {@link AnimeService#upsertAnime}：这里原本有一份自己的
 * {@code toAnime} 映射副本，与 {@code AnimeService} / {@code DataRefreshService}
 * 里的那两份各不相同，season/status/rank 三个字段就是因为"要改四处"而一处都没改。
 */
@Component
public class CachePreloader {

    private static final Logger log = LoggerFactory.getLogger(CachePreloader.class);

    // 常见动漫搜索词，覆盖不同类型
    private static final String[] SEED_KEYWORDS = {
        "进击的巨人", "鬼灭之刃", "咒术回战", "间谍过家家",
        "葬送的芙莉莲", "我推的孩子", "药屋少女", "Re:从零",
        "刀剑神域", "命运石之门", "CLANNAD", "钢之炼金术师",
        "日常", "轻音少女", "冰菓", "紫罗兰永恒花园",
        "一拳超人", "灵能百分百", "排球少年", "海贼王",
        "火影忍者", "死神", "龙珠", "EVA",
        "魔法少女", "物语", "Fate", "异世界",
        "夏日", "在地下城", "某科学的", "为美好的世界"
    };

    /**
     * 当季新番搜索词. 年份是**算出来的**, 不写死.
     *
     * <p>原先这里是 "2026" / "2025" / "2026年" / "2025年" 四个字面量, 注释还写着
     * "定期更新" —— 而"定期更新"的意思是"到期了要有人记得改", 没人记得的时候它
     * 不会报错, 只是悄悄开始搜去年的番. 现在取当前年份和上一年, 跨年那天自己就对了.
     * (季度级的偏移在 DataRefreshService.buildSeasonalKeywords 里按月份算, 那是
     * 定时任务该管的事; 这里是启动时的一次性补齐, 用年粒度就够.)
     */
    private static String[] seasonalKeywords() {
        int year = Year.now().getValue();
        return new String[] {
            String.valueOf(year), String.valueOf(year - 1),
            year + "年", (year - 1) + "年",
            "剧场版", "新番", "OVA", "动画电影"
        };
    }

    /**
     * 预加载专用线程, 不复用 ForkJoinPool.commonPool.
     *
     * <p>原来写的是 {@code CompletableFuture.runAsync(...)}, 默认落在 commonPool 上 ——
     * 那是**整个 JVM 共用**的池, 默认线程数是 CPU 核数 - 1, 并行流(parallelStream)
     * 用的也是它. 而这里要连着跑几分钟, 中间大量 {@code Thread.sleep}(对 Bangumi 限速):
     * sleep 中的任务照样占着一个 worker, 在 1-2 核的机器上等于把 commonPool 占满,
     * 别处任何并行流都得排在后面 —— 症状是"启动后头几分钟首页偶尔卡", 而两件事
     * 之间看不出联系. 给它自己的池, 池里只有它一个用户, 谁也抢不到谁.
     *
     * <p>线程设成 daemon: 预加载是"有更好、没有也能照常服务"的事, 不该拖住 JVM 退出.
     */
    private final ExecutorService preloadExecutor = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "cache-preloader");
        t.setDaemon(true);
        return t;
    });

    private final BangumiApiClient apiClient;
    private final AnimeRepository animeRepo;
    private final AnimeService animeService;

    /**
     * 启动后是否自动联网补充数据, 默认开着.
     *
     * <p>存在的理由只有一个: 数 SQL 的集成测试要把它关掉. 它是**异步**的, 在用例
     * 跑的同时往 anime / anime_tag 里插数据, 而那些插入的语句和用例自己发的语句
     * 算在同一个 SessionFactory 的统计里 —— 于是"这个动作发了几条 SQL"会变成一个
     * 随后台线程进度变化的数字. QueryCountIntegrationTest 就撞在这上面, 它关掉这个开关
     * 之后断言才成立(见那里的 properties).
     */
    @Value("${anitrack.preload.enabled:true}")
    private boolean enabled;

    public CachePreloader(BangumiApiClient apiClient, AnimeRepository animeRepo, AnimeService animeService) {
        this.apiClient = apiClient;
        this.animeRepo = animeRepo;
        this.animeService = animeService;
    }

    @PreDestroy
    void shutdown() {
        // 用 shutdownNow 而不是 shutdown: 这个任务大多数时间在 sleep, 优雅关闭
        // 等于什么都不做. 中断掉它, sleep 会立刻抛出, 任务在几毫秒内结束.
        preloadExecutor.shutdownNow();
    }

    @EventListener(ApplicationReadyEvent.class)
    public void preload() {
        if (!enabled) {
            log.info("预加载已关闭(anitrack.preload.enabled=false), 跳过");
            return;
        }
        String[] seasonal = seasonalKeywords();
        preloadExecutor.execute(() -> {
            long startCount = animeRepo.count();
            log.info("当前缓存 {} 条，开始补充...", startCount);

            int added = 0;

            // 第一步：用当季关键词搜索新番
            for (String kw : seasonal) {
                try {
                    SearchResponse resp = apiClient.searchSubjects(kw, 1, 20);
                    if (resp != null && resp.getData() != null) {
                        for (SubjectDTO dto : resp.getData()) {
                            if (!animeRepo.existsById(dto.getId())) {
                                animeService.upsertAnime(dto);
                                added++;
                            }
                        }
                    }
                    Thread.sleep(500);
                } catch (Exception e) {
                    log.debug("当季关键词 '{}' 跳过: {}", kw, e.getMessage());
                }
            }
            log.info("当季关键词: 新增 {} 条", added);

            // 第二步：多页拉取热门排行（空关键词=浏览模式）
            try {
                log.info("拉取 Bangumi 热门排行榜...");
                for (int page = 1; page <= 3; page++) {
                    SearchResponse rankResp = apiClient.searchSubjects("", page, 20);
                    int batchAdded = 0;
                    if (rankResp != null && rankResp.getData() != null) {
                        for (SubjectDTO dto : rankResp.getData()) {
                            if (!animeRepo.existsById(dto.getId())) {
                                animeService.upsertAnime(dto);
                                added++;
                                batchAdded++;
                            }
                        }
                        log.info("  排行榜第{}页: +{}条", page, batchAdded);
                        if (rankResp.getData().size() < 20) break; // 没更多数据了
                    }
                    Thread.sleep(800);
                }
            } catch (Exception e) {
                log.debug("拉取排行榜跳过: {}", e.getMessage());
            }

            // 第三步：用种子关键词补充经典番剧
            // 如果已有足够数据，跳过部分关键词
            int toSkip = startCount > 200 ? SEED_KEYWORDS.length / 2
                       : startCount > 100 ? SEED_KEYWORDS.length / 3
                       : 0;

            for (int i = toSkip; i < SEED_KEYWORDS.length; i++) {
                try {
                    SearchResponse resp = apiClient.searchSubjects(SEED_KEYWORDS[i], 1, 10);
                    if (resp == null || resp.getData() == null) continue;

                    for (SubjectDTO dto : resp.getData()) {
                        if (!animeRepo.existsById(dto.getId())) {
                            animeService.upsertAnime(dto);
                            added++;
                        }
                    }
                    Thread.sleep(600); // 礼貌限速
                } catch (Exception e) {
                    log.debug("关键词 '{}' 搜索跳过: {}", SEED_KEYWORDS[i], e.getMessage());
                }
            }

            log.info("缓存补充完成，新增 {} 条，共 {} 条", added, animeRepo.count());

            // 预热缓存: 首次访问不再等待
            try { animeService.getRanking(30); animeService.getLatest(12); animeService.getAllTags(); animeService.getCalendar(); }
            catch (Exception e) { log.debug("缓存预热跳过: {}", e.getMessage()); }
            log.info("缓存预热完成");
        });
    }

}
