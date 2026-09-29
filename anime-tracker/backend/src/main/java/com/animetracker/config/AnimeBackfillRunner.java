package com.animetracker.config;

import com.animetracker.service.AnimeBackfillService;
import com.animetracker.service.AnimeBackfillService.BackfillResult;
import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.util.concurrent.TimeUnit;

/**
 * 启动时跑一轮全量回填, 当且仅当 {@code anitrack.backfill.enabled=true}.
 *
 * <p>为什么单开一个类, 而不是在 {@link AnimeBackfillService} 里加个
 * {@code @EventListener}: 回填结束要清缓存, 而清缓存靠的是 {@code @CacheEvict} 代理.
 * 类自己调自己不走代理, 注解等于没写. 放在这里, 调用穿过容器, 才真的会清.
 *
 * <p>为什么不用 {@code @Async}: 那要额外开 {@code @EnableAsync}, 而且拿不到线程句柄 ——
 * 下面 {@link #stop()} 要的就是那个句柄. 一个裸 Thread 在这里更短也更清楚.
 *
 * <p>为什么失败/中断都不阻断: 回填是**增量改善**, 不是启动的前提. 库里只有部分数据时
 * 站点照常能用; 回填没跑完, 无非是继续用手上这些. 为它让服务起不来是反的.
 *
 * <p>线程是 daemon 且在这里被 {@link #stop()} 中断: 关停时最多损失当前这一页(每页 50 条),
 * 而这一路本来就是幂等可续的 —— 代价只是重跑时多翻一页. 反过来, 非 daemon 会让
 * Ctrl+C 之后 JVM 干等十分钟, 那才是最难受的.
 */
@Component
@ConditionalOnProperty(name = "anitrack.backfill.enabled", havingValue = "true")
public class AnimeBackfillRunner implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(AnimeBackfillRunner.class);

    /** 关停时等它自己收尾的上限. 一次请求的 read-timeout 是 90 秒, 但中断会让 sleep 立刻返回 */
    private static final long STOP_WAIT_SECONDS = 10;

    private final AnimeBackfillService backfillService;

    private volatile Thread worker;

    public AnimeBackfillRunner(AnimeBackfillService backfillService) {
        this.backfillService = backfillService;
    }

    @Override
    public void run(ApplicationArguments args) {
        Thread t = new Thread(this::crawl, "anime-backfill");
        t.setDaemon(true);
        worker = t;
        t.start();
        log.info("[全量回填] 已按 anitrack.backfill.enabled=true 在后台启动, 不挡启动流程");
    }

    private void crawl() {
        try {
            BackfillResult r = backfillService.backfill();
            if (r.complete()) {
                log.info("[全量回填] 跑完了: 库内 {} → {} 条", r.countBefore(), r.countAfter());
            } else {
                // 没跑完就把"从哪接着跑"印在最显眼的地方 —— 这条日志是下一次的输入
                log.warn("[全量回填] 没跑完 ({}): 库内 {} 条, Bangumi 报 {} 条. 下次设 BACKFILL_START_OFFSET={} 接着跑",
                        r.stopped().label(), r.countAfter(), r.reportedTotal(), r.nextOffset());
            }
        } catch (Exception e) {
            log.error("[全量回填] 异常退出: {}", e.getMessage(), e);
        } finally {
            worker = null;
        }
    }

    @PreDestroy
    void stop() {
        Thread t = worker;
        if (t == null) {
            return;
        }
        log.info("[全量回填] 应用要关了, 通知它停下");
        t.interrupt();
        try {
            t.join(TimeUnit.SECONDS.toMillis(STOP_WAIT_SECONDS));
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
