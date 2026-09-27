package com.animetracker.config;

import com.animetracker.dto.BangumiDTO.*;
import com.animetracker.entity.Anime;
import com.animetracker.repository.AnimeRepository;
import com.animetracker.service.AnimeService;
import com.animetracker.service.BangumiApiClient;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.stream.Collectors;

/**
 * 启动后静默从 Bangumi 拉取动漫数据到本地缓存。
 * 策略：用常见搜索词分批搜索，积累足够数据后首页即可走本地。
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

    // 当季新番搜索词（定期更新）
    private static final String[] SEASONAL_KEYWORDS = {
        "2026", "2025", "2026年", "2025年",
        "剧场版", "新番", "OVA", "动画电影"
    };

    private final BangumiApiClient apiClient;
    private final AnimeRepository animeRepo;
    private final AnimeService animeService;

    public CachePreloader(BangumiApiClient apiClient, AnimeRepository animeRepo, AnimeService animeService) {
        this.apiClient = apiClient;
        this.animeRepo = animeRepo;
        this.animeService = animeService;
    }

    @EventListener(ApplicationReadyEvent.class)
    public void preload() {
        CompletableFuture.runAsync(() -> {
            long startCount = animeRepo.count();
            log.info("当前缓存 {} 条，开始补充...", startCount);

            int added = 0;

            // 第一步：用当季关键词搜索新番
            for (String kw : SEASONAL_KEYWORDS) {
                try {
                    SearchResponse resp = apiClient.searchSubjects(kw, 1, 20);
                    if (resp != null && resp.getData() != null) {
                        for (SubjectDTO dto : resp.getData()) {
                            if (!animeRepo.existsById(dto.getId())) {
                                animeService.saveAnimeWithTags(toAnime(dto));
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
                                animeService.saveAnimeWithTags(toAnime(dto));
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
                            animeService.saveAnimeWithTags(toAnime(dto));
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

    private Anime toAnime(SubjectDTO dto) {
        Anime a = Anime.builder().id(dto.getId()).build();
        a.setTitle(dto.getName());
        a.setTitleCn(dto.getNameCn());
        a.setSummary(dto.getSummary());
        if (dto.getImages() != null) {
            a.setCoverUrl(dto.getImages().getLarge() != null
                    ? dto.getImages().getLarge()
                    : dto.getImages().getCommon());
        }
        a.setDate(dto.getDate());
        a.setPlatform(dto.getPlatform());
        a.setTotalEpisodes(dto.getTotalEpisodes());
        if (dto.getRating() != null) {
            a.setRating(dto.getRating().getScore());
            a.setRatingCount(dto.getRating().getTotal());
        }
        if (dto.getTags() != null) {
            a.setTags(dto.getTags().stream()
                    .map(TagDTO::getName)
                    .collect(Collectors.joining(",")));
        }
        a.setCacheUpdatedAt(LocalDateTime.now());
        return a;
    }
}
