package com.animetracker.controller;

import com.animetracker.dto.ApiResponse;
import com.animetracker.dto.BangumiDTO.CalendarDay;
import com.animetracker.dto.response.AnimeDTO;
import com.animetracker.dto.response.AnimeMapper;
import com.animetracker.dto.response.EpisodeDTO;
import com.animetracker.entity.Anime;
import com.animetracker.entity.Episode;
import com.animetracker.service.AnimeService;
import com.animetracker.util.TagTranslationUtil;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.web.bind.annotation.*;

import java.util.*;
import java.util.stream.Collectors;

/**
 * Bangumi/番剧内容接口.
 *
 * <p>负责番剧搜索、详情、剧集、排行、日历、标签等内容的对外暴露。
 * 所有响应使用类型化 DTO (不再使用裸 Map), 转换逻辑委托给 {@link AnimeMapper}.
 */
@RestController
@RequestMapping("/api/bangumi")
public class BangumiController {

    private static final Logger log = LoggerFactory.getLogger(BangumiController.class);

    private final AnimeService animeService;
    private final AnimeMapper animeMapper;

    public BangumiController(AnimeService animeService, AnimeMapper animeMapper) {
        this.animeService = animeService;
        this.animeMapper = animeMapper;
    }

    // ══════════ 搜索 ══════════

    @SuppressWarnings("unchecked")
    @GetMapping("/search")
    public ApiResponse<Map<String, Object>> search(
            @RequestParam(defaultValue = "") String keyword,
            @RequestParam(defaultValue = "1") Integer page,
            @RequestParam(defaultValue = "20") Integer limit) {

        Map<String, Object> result = animeService.searchAnime(keyword, page, limit);
        List<Anime> list = (List<Anime>) result.get("list");
        int total = ((Number) result.getOrDefault("total", 0)).intValue();

        // 前端 Search.vue 期望格式: { data: { list: [...], total: N } }
        Map<String, Object> body = Map.of(
                "list", animeMapper.toListItems(list),
                "total", total,
                "page", page);
        return ApiResponse.success(body);
    }

    // ══════════ 详情 ══════════

    @GetMapping("/subject/{subjectId}")
    public ApiResponse<AnimeDTO> getSubjectDetail(@PathVariable Integer subjectId) {
        Anime anime = animeService.getAnimeDetail(subjectId);
        if (anime == null) {
            return ApiResponse.notFound("番剧不存在");
        }
        return ApiResponse.success(animeMapper.toDetail(anime));
    }

    // ══════════ 剧集列表 ══════════

    @GetMapping("/subject/{subjectId}/episodes")
    public ApiResponse<List<EpisodeDTO>> getEpisodes(@PathVariable Integer subjectId) {
        List<Episode> episodes = animeService.getEpisodes(subjectId);
        List<EpisodeDTO> list = episodes.stream()
                .map(EpisodeDTO::from)
                .collect(Collectors.toList());
        return ApiResponse.success(list);
    }

    // ══════════ 排行榜 ══════════

    @GetMapping("/ranking")
    public ApiResponse<List<AnimeDTO>> getRanking(
            @RequestParam(defaultValue = "rank") String sort,
            @RequestParam(defaultValue = "20") Integer limit) {

        List<Anime> list = "date".equals(sort)
                ? animeService.getLatest(limit)
                : animeService.getRanking(limit);
        return ApiResponse.success(animeMapper.toListItems(list));
    }

    // ══════════ 每日放送 ══════════

    @GetMapping("/calendar")
    public ApiResponse<List<CalendarDay>> getCalendar() {
        return ApiResponse.success(animeService.getCalendar());
    }

    // ══════════ 标签 ══════════

    @GetMapping("/tags")
    public ApiResponse<List<Map<String, Object>>> getTags() {
        List<Map<String, Object>> raw = animeService.getAllTags();
        List<Map<String, Object>> translated = raw.stream()
                .map(tag -> Map.of(
                        "name", TagTranslationUtil.translate((String) tag.get("name")),
                        "count", tag.get("count")))
                .collect(Collectors.toList());
        return ApiResponse.success(translated);
    }

    @GetMapping("/by-tag")
    public ApiResponse<List<AnimeDTO>> getByTag(@RequestParam String tag) {
        Set<String> enTags = TagTranslationUtil.reverseTranslateAll(tag);
        return ApiResponse.success(animeMapper.toListItems(animeService.getByTags(enTags)));
    }

    // ══════════ 筛选 ══════════

    @GetMapping("/filter-meta")
    public ApiResponse<Map<String, Object>> getFilterMeta() {
        return ApiResponse.success(animeService.getFilterMeta());
    }

    @GetMapping("/filter")
    public ApiResponse<List<AnimeDTO>> getFiltered(
            @RequestParam(required = false) String year,
            @RequestParam(required = false) String season,
            @RequestParam(required = false) String status,
            @RequestParam(required = false) String tag,
            @RequestParam(defaultValue = "rank") String sort) {
        return ApiResponse.success(
                animeMapper.toListItems(animeService.getFiltered(year, season, status, tag, sort)));
    }
}
