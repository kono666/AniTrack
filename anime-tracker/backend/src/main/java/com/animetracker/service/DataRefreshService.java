package com.animetracker.service;

import com.animetracker.dto.BangumiDTO.*;
import com.animetracker.entity.Anime;
import com.animetracker.repository.AnimeRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.cache.annotation.CacheEvict;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.stream.Collectors;

/**
 * 定时增量刷新动漫数据.
 *
 * 每小时从 Bangumi API 拉取:
 *   1. 当季新番 (当前年份+月份关键词)
 *   2. 热门排行增量
 *   3. 正在放送的番剧更新
 *
 * 所有操作仅追加/更新，不删除已有数据。
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
                        if (animeRepo.existsById(dto.getId())) {
                            // 更新已有记录
                            Anime existing = animeRepo.findById(dto.getId()).orElse(null);
                            if (existing != null) {
                                mergeUpdate(existing, dto);
                                animeService.saveAnimeWithTags(existing);
                                updated++;
                            }
                        } else {
                            animeService.saveAnimeWithTags(toAnime(dto));
                            added++;
                        }
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
                        if (!animeRepo.existsById(dto.getId())) {
                            animeService.saveAnimeWithTags(toAnime(dto));
                            added++;
                        }
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
                            if (!animeRepo.existsById(item.getId())) {
                                var detail = apiClient.getSubjectDetail(item.getId());
                                if (detail != null && detail.getId() != null) {
                                    animeService.saveAnimeWithTags(toAnime(detail));
                                    added++; calAdded++;
                                }
                            }
                            Thread.sleep(300); // 礼貌限速
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
                    if (!animeRepo.existsById(dto.getId())) {
                        animeService.saveAnimeWithTags(toAnime(dto));
                        added++;
                    }
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

    private void mergeUpdate(Anime a, SubjectDTO dto) {
        a.setTitle(dto.getName());
        if (dto.getNameCn() != null) a.setTitleCn(dto.getNameCn());
        if (dto.getSummary() != null) a.setSummary(dto.getSummary());
        if (dto.getDate() != null) a.setDate(dto.getDate());
        if (dto.getPlatform() != null) a.setPlatform(dto.getPlatform());
        if (dto.getTotalEpisodes() != null) a.setTotalEpisodes(dto.getTotalEpisodes());
        if (dto.getRating() != null) {
            a.setRating(dto.getRating().getScore());
            a.setRatingCount(dto.getRating().getTotal());
        }
        if (dto.getTags() != null) {
            a.setTags(dto.getTags().stream().map(TagDTO::getName).collect(Collectors.joining(",")));
        }
        a.setCacheUpdatedAt(LocalDateTime.now());
    }

    private Anime toAnime(SubjectDTO dto) {
        Anime a = Anime.builder().id(dto.getId()).build();
        a.setTitle(dto.getName());
        a.setTitleCn(dto.getNameCn());
        a.setSummary(dto.getSummary());
        if (dto.getImages() != null) {
            a.setCoverUrl(dto.getImages().getLarge() != null
                    ? dto.getImages().getLarge() : dto.getImages().getCommon());
        }
        a.setDate(dto.getDate());
        a.setPlatform(dto.getPlatform());
        a.setTotalEpisodes(dto.getTotalEpisodes());
        if (dto.getRating() != null) {
            a.setRating(dto.getRating().getScore());
            a.setRatingCount(dto.getRating().getTotal());
        }
        if (dto.getTags() != null) {
            a.setTags(dto.getTags().stream().map(TagDTO::getName).collect(Collectors.joining(",")));
        }
        a.setCacheUpdatedAt(LocalDateTime.now());
        return a;
    }
}
