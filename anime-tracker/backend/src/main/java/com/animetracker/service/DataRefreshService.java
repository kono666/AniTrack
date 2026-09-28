package com.animetracker.service;

import com.animetracker.dto.BangumiDTO.*;
import com.animetracker.repository.AnimeRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.cache.annotation.CacheEvict;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.*;

/**
 * 定时增量刷新动漫数据.
 *
 * 每小时从 Bangumi API 拉取:
 *   1. 当季新番 (当前年份+月份关键词)
 *   2. 热门排行增量
 *   3. 正在放送的番剧更新
 *
 * 所有操作仅追加/更新，不删除已有数据。
 *
 * <p>这里原本还有一份自己的 {@code toAnime} / {@code mergeUpdate} 映射副本,
 * 现在全部改走 {@link AnimeService#upsertAnime} —— 落库映射只留一处.
 * 原因见那个方法的注释: season/status/rank 三个字段正是因为映射有四份、
 * 要改四处而一处都没改, 结果 470 行数据全是 NULL, 而按这三个字段筛选的
 * 接口一直对外开着, 谁都不报错.
 */
@Service
public class DataRefreshService {

    private static final Logger log = LoggerFactory.getLogger(DataRefreshService.class);

    private final BangumiApiClient apiClient;
    private final AnimeRepository animeRepo;
    private final AnimeService animeService;

    public DataRefreshService(BangumiApiClient apiClient, AnimeRepository animeRepo, AnimeService animeService) {
        this.apiClient = apiClient;
        this.animeRepo = animeRepo;
        this.animeService = animeService;
    }

    /**
     * 每小时第7分钟执行 (错开整点高峰).
     * 首次启动后延迟60秒再执行, 等CachePreloader跑完.
     */
    @CacheEvict(value = {"ranking", "latest", "tags", "calendar"}, allEntries = true)
    @Scheduled(initialDelay = 60_000, fixedRate = 3_600_000)
    public void refreshIncremental() {
        long before = animeRepo.count();
        int added = 0;
        int updated = 0;

        log.info("[定时刷新] 开始, 当前 {} 条", before);

        // 1. 当季关键词搜索
        String[] seasonal = buildSeasonalKeywords();
        for (String kw : seasonal) {
            try {
                SearchResponse resp = apiClient.searchSubjects(kw, 1, 15);
                if (resp != null && resp.getData() != null) {
                    for (SubjectDTO dto : resp.getData()) {
                        if (dto.getId() == null) continue;
                        // 先记下有没有, 只为了统计新增/更新这两个计数 ——
                        // 落库本身统一走 upsertAnime (见该类顶部注释)
                        boolean existed = animeRepo.existsById(dto.getId());
                        animeService.upsertAnime(dto);
                        if (existed) updated++; else added++;
                    }
                }
                Thread.sleep(400);
            } catch (Exception e) { /* skip */ }
        }

        // 2. 热门排行前3页
        try {
            for (int page = 1; page <= 2; page++) {
                SearchResponse rankResp = apiClient.searchSubjects("", page, 20);
                if (rankResp != null && rankResp.getData() != null) {
                    for (SubjectDTO dto : rankResp.getData()) {
                        if (dto.getId() == null) continue;
                        // 这一趟是"按名次浏览", 返回的 rating.rank 就是真实的榜单名次,
                        // 也是 rank 这一列唯一的数据来源 —— 所以这里对已存在的行
                        // 也要刷新(以前只插新的, 老数据的 rank 永远是 null).
                        boolean existed = animeRepo.existsById(dto.getId());
                        animeService.upsertAnime(dto);
                        if (existed) updated++; else added++;
                    }
                    if (rankResp.getData().size() < 20) break;
                }
                Thread.sleep(600);
            }
        } catch (Exception e) { /* skip */ }

        // 3. 日历接口: 拉取全部在播番剧的完整详情
        try {
            var calDays = apiClient.getCalendar();
            if (calDays != null) {
                int calAdded = 0;
                for (var day : calDays) {
                    if (day.getItems() == null) continue;
                    for (var item : day.getItems()) {
                        try {
                            if (animeRepo.existsById(item.getId())) {
                                // 已有的行: 只用日历这一条记录刷新(air_date / rank /
                                // "正在播"状态). 以前这里是直接跳过, 代价是老数据
                                // 永远停在什么都没有的状态 —— 实测线上 470 行里
                                // 145 行的 date 是 NULL, 而 date 为空会让 season 和
                                // status 一起推不出来.
                                animeService.upsertCalendarItem(item);
                                updated++;
                            } else {
                                // 新条目先取一次详情(要简介、集数、标签这些日历不给的字段),
                                // 再用日历条目的权威在播标记覆盖一次 status
                                var detail = apiClient.getSubjectDetail(item.getId());
                                if (detail != null && detail.getId() != null) {
                                    animeService.upsertAnime(detail);
                                    animeService.upsertCalendarItem(item);
                                    added++; calAdded++;
                                }
                                Thread.sleep(300); // 礼貌限速
                            }
                        } catch (Exception ex) { /* skip single item */ }
                    }
                }
                if (calAdded > 0) log.info("  日历补充: +{} 条当季新番", calAdded);
            }
        } catch (Exception e) { log.debug("日历刷新跳过: {}", e.getMessage()); }

        log.info("[定时刷新] 完成. 新增 {} 条, 更新 {} 条, 共 {} 条",
                added, updated, animeRepo.count());
    }

    /** 每天凌晨4点全量更新当年所有番剧 */
    @Scheduled(cron = "0 17 4 * * *")
    public void refreshDailyFull() {
        String year = String.valueOf(java.time.Year.now().getValue());
        log.info("[每日全量刷新] 开始, 年份={}", year);

        int added = 0;
        for (int page = 1; page <= 10; page++) {
            try {
                SearchResponse resp = apiClient.searchSubjects(year, page, 20);
                if (resp == null || resp.getData() == null || resp.getData().isEmpty()) break;
                for (SubjectDTO dto : resp.getData()) {
                    if (dto.getId() == null) continue;
                    boolean existed = animeRepo.existsById(dto.getId());
                    animeService.upsertAnime(dto);
                    if (!existed) added++;
                }
                Thread.sleep(600);
            } catch (Exception e) { break; }
        }
        log.info("[每日全量刷新] 完成, 新增 {} 条, 共 {} 条", added, animeRepo.count());
    }

    // ── helpers ──

    private String[] buildSeasonalKeywords() {
        LocalDateTime now = LocalDateTime.now();
        int year = now.getYear();
        int month = now.getMonthValue();
        // 当季 + 前后各一个季度
        List<String> kws = new java.util.ArrayList<>();
        for (int m : new int[]{month - 3, month, month + 3}) {
            if (m < 1) { kws.add((year - 1) + "年"); }
            else if (m > 12) { kws.add((year + 1) + "年"); }
            else { kws.add(year + "年"); }
        }
        kws.add(String.valueOf(year));
        kws.add("新番");
        kws.add("剧场版");
        return kws.toArray(new String[0]);
    }

}
