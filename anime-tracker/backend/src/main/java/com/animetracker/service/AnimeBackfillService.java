package com.animetracker.service;

import com.animetracker.dto.BangumiDTO.SearchResponse;
import com.animetracker.dto.BangumiDTO.SubjectDTO;
import com.animetracker.repository.AnimeRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.cache.annotation.CacheEvict;
import org.springframework.stereotype.Service;

import java.util.List;

/**
 * 一次性全量回填: 把 Bangumi 的**全部**动画条目拉进本地库.
 *
 * <p>为什么需要它: 站内的 anime 表一直是「热门 + 按需」缓存, 约 470 条, 而全量是
 * 29,378 条 —— 站内约 1.6%. 三个定时任务写的都是写死的范围(当季关键词、排行前几页、
 * 当年 10 页), 也就是说**代码里此前没有任何一条拉全量的路径**: 定时更新一直在跑,
 * 缺的从来不是定时, 是范围. 结果就是搜索与浏览都只在那一小撮数据里打转.
 *
 * <p>为什么是"一次性"而不是又一个定时任务: 追新由 {@link DataRefreshService} 那三个
 * 任务负责, 它们拉的是"最近在动的那些". 全量回填解决的是**历史存量**——那批不会
 * 再变化的条目, 拉一次就够了, 再拉是白拉. 要重复跑的话它也是幂等的(见下).
 *
 * <p>幂等与可续: 每一条都走 {@link AnimeService#upsertAnime}, 有就刷新、没有就新建,
 * 所以中断之后重跑不会产生重复行, 也不会把已有的字段抹掉. 中断的代价只是"这一轮
 * 白翻了那几页"—— 想少白翻就设 {@code anitrack.backfill.start-offset}, 从日志里
 * 最后那条心跳的 offset, 或者 {@link BackfillResult#nextOffset()} 接着来.
 *
 * <p>节奏: 默认每次请求之间停 {@code anitrack.backfill.delay-ms}(默认 1000ms).
 * 29,378 条按每页 50 算约 588 次请求, 也就是十分量级. 这个数字是**刻意**的:
 * Bangumi 是别人家的服务, 我们只是它的一个缓存用户, 一次回填不值得让它为我们
 * 扛一次压力测试. 想快就把 delay-ms 调小, 但那是你在替对方做决定.
 */
@Service
public class AnimeBackfillService {

    private static final Logger log = LoggerFactory.getLogger(AnimeBackfillService.class);

    /** 每翻这么多页打一条心跳. 全量约 588 页, 也就是约 30 条心跳 —— 足够看出它在动, 又不至于把日志淹掉 */
    private static final int HEARTBEAT_PAGES = 20;

    private final BangumiApiClient apiClient;
    private final AnimeRepository animeRepo;
    private final AnimeService animeService;

    private final int startOffset;
    private final int pageSize;
    private final int maxPages;
    private final long delayMs;

    public AnimeBackfillService(BangumiApiClient apiClient,
                                AnimeRepository animeRepo,
                                AnimeService animeService,
                                @Value("${anitrack.backfill.start-offset:0}") int startOffset,
                                @Value("${anitrack.backfill.page-size:50}") int pageSize,
                                @Value("${anitrack.backfill.max-pages:700}") int maxPages,
                                @Value("${anitrack.backfill.delay-ms:1000}") long delayMs) {
        this.apiClient = apiClient;
        this.animeRepo = animeRepo;
        this.animeService = animeService;
        this.startOffset = Math.max(startOffset, 0);
        this.pageSize = Math.min(Math.max(pageSize, 1), 50);   // 浏览接口实测上限就是 50
        this.maxPages = Math.max(maxPages, 1);
        this.delayMs = Math.max(delayMs, 0);
    }

    /** 为什么会停. 用枚举而不是一串中文: 调用方要据此判断"是跑完了还是半路断了", 比字符串可靠 */
    public enum StopReason {
        /** 数据真的翻完了 —— 这是唯一一种"这轮算跑完了" */
        END_OF_DATA("翻到底了"),
        /** 请求没成(网络、限流、5xx). **不能当成翻到底** —— 后面还有数据 */
        REQUEST_FAILED("请求失败"),
        /** 到了配置的页数上限, 后面还有数据 */
        MAX_PAGES("到达 max-pages 上限"),
        /** 线程被中断(应用正在关停) */
        INTERRUPTED("被中断");

        private final String label;

        StopReason(String label) {
            this.label = label;
        }

        public String label() {
            return label;
        }
    }

    /** 这一轮的结果. 返回而不是只打日志, 是为了让触发它的人(和测试)能拿到一句话的总结 */
    public record BackfillResult(int pages, int fetched, int nextOffset,
                                 Integer reportedTotal, long countBefore, long countAfter,
                                 StopReason stopped) {
        /** 只有"数据翻完了"才算跑完. 其余三种停下来时, 库里都比 Bangumi 少一截 */
        public boolean complete() {
            return stopped == StopReason.END_OF_DATA;
        }
    }

    /**
     * 跑一轮全量回填. 同步方法, 会阻塞很久(十分量级) —— 谁来调它, 谁负责别把启动线程占住.
     *
     * <p>{@code @CacheEvict} 放在这里而不是循环里: 一轮回填期间缓存里那几份空榜单
     * 至少是**自洽**的(库确实还小), 中途清掉只会让并发访问反复重算. 回填结束再一次性
     * 清, 下一次请求就会从 29,378 条里重新算.
     */
    @CacheEvict(value = {"ranking", "latest", "tags", "calendar"}, allEntries = true)
    public BackfillResult backfill() {
        long before = animeRepo.count();
        int offset = startOffset;
        int pages = 0;
        int fetched = 0;
        Integer total = null;
        StopReason stopped = StopReason.MAX_PAGES;

        log.info("[全量回填] 开始: start-offset={}, 每页 {} 条, 间隔 {} ms, 库内现有 {} 条, 上限 {} 页",
                offset, pageSize, delayMs, before, maxPages);

        while (pages < maxPages) {
            SearchResponse resp = apiClient.browseSubjects(offset, pageSize);
            if (resp == null || resp.getData() == null) {
                // 请求没成 —— 不是"没有更多了". 停下来并说清停在哪, 让下一次能接着跑;
                // 硬着头皮继续翻只会把"网络抖了一下"变成"后面几万条静默丢失".
                stopped = StopReason.REQUEST_FAILED;
                break;
            }

            List<SubjectDTO> data = resp.getData();
            if (data.isEmpty()) {
                stopped = StopReason.END_OF_DATA;
                break;
            }

            if (total == null) {
                total = resp.getTotal();
                log.info("[全量回填] Bangumi 报总数 {} 条", total);
            }

            for (SubjectDTO dto : data) {
                if (dto == null || dto.getId() == null) {
                    continue;   // 没有 id 的行落库没有意义(anime.id 就是 Bangumi 的 subject_id)
                }
                animeService.upsertAnime(dto);
                fetched++;   // 数**真正落库的**, 不是这一页的长度: 否则日志里"抓取 N 条"和"库内 M 条"各说各话
            }

            // offset 则相反, 必须按**对端的页码口径**推进(也就是 data.size(), 含被跳过的那几行),
            // 而且不能按 pageSize: 少算一条会把后面几万条集体错位一格, 多算一条会跳过一段.
            // 两种都无声无息 —— 与"失败即停"防的是同一类事.
            offset += data.size();
            pages++;

            if (pages % HEARTBEAT_PAGES == 0) {
                log.info("[全量回填] 已翻 {} 页, 抓取 {} 条, offset={}, 库内 {} 条",
                        pages, fetched, offset, animeRepo.count());
            }

            if (total != null && offset >= total) {
                stopped = StopReason.END_OF_DATA;
                break;
            }

            if (!sleepBetweenRequests()) {
                stopped = StopReason.INTERRUPTED;
                break;
            }
        }

        if (stopped != StopReason.END_OF_DATA) {
            log.warn("[全量回填] {} (下一步从 offset={} 接着跑), 停在这里", stopped.label(), offset);
        }

        long after = animeRepo.count();
        log.info("[全量回填] 结束: 翻 {} 页, 抓取 {} 条, 库内 {} → {} 条 (Bangumi 报总数 {}), 原因: {}",
                pages, fetched, before, after, total, stopped.label());

        return new BackfillResult(pages, fetched, offset, total, before, after, stopped);
    }

    /** @return false 表示被中断, 调用方应停止 */
    private boolean sleepBetweenRequests() {
        if (delayMs <= 0) {
            return !Thread.currentThread().isInterrupted();
        }
        try {
            Thread.sleep(delayMs);
            return true;
        } catch (InterruptedException e) {
            // 恢复中断位再返回: 吞掉 InterruptedException 而不恢复, 是让线程池
            // 关不掉的那种写法 —— 关停信号会丢在这里.
            Thread.currentThread().interrupt();
            return false;
        }
    }
}
