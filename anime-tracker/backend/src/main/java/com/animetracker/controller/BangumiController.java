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
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.*;

import java.util.*;
import java.util.stream.Collectors;

/**
 * Bangumi/番剧内容接口.
 *
 * <p>负责番剧搜索、详情、剧集、排行、日历、标签等内容的对外暴露。
 * 所有响应使用类型化 DTO (不再使用裸 Map), 转换逻辑委托给 {@link AnimeMapper}.
 *
 * <p>类上这个 @Validated 是查询参数上那几个 @Min/@Max 生效的前提: 没有它,
 * 方法参数上的注解会被静默忽略 —— 校验看着写了, 实际一次都不跑.
 * 配套地, GlobalExceptionHandler 里有一条 ConstraintViolationException 的处理器,
 * 否则校验生效了也只会对外显示成 500.
 */
@Validated
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

    /**
     * 搜索.
     *
     * <p>page 必须从 1 开始: 翻页是 (page-1)*limit, page=0 会算出负的起点,
     * 到了 subList(-20, 0) 就是一次 IndexOutOfBoundsException —— 对外是 500,
     * 而对一个「参数写错了」的请求回 500 是最糟的一档: 前端只会提示「服务异常,
     * 请稍后再试」, 写调用方的人根本想不到是自己页码从 0 开始了.
     *
     * <p>limit 封到 50: 这个值直接决定一次查出多少条, 不封顶的话一个请求就能
     * 让服务端和调用方各自扛一份任意大的结果集.
     */
    @SuppressWarnings("unchecked")
    @GetMapping("/search")
    public ApiResponse<Map<String, Object>> search(
            @RequestParam(defaultValue = "") String keyword,
            @RequestParam(defaultValue = "1")
            @Min(value = 1, message = "页码从 1 开始") Integer page,
            @RequestParam(defaultValue = "20")
            @Min(value = 1, message = "每页条数不能小于 1")
            @Max(value = 50, message = "每页条数不能超过 50") Integer limit) {

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

    /** limit 同样只封下界: 负数会走到 subList(0, -n), 与搜索那边是同一个 500 */
    @GetMapping("/ranking")
    public ApiResponse<List<AnimeDTO>> getRanking(
            @RequestParam(defaultValue = "rank") String sort,
            @RequestParam(defaultValue = "20")
            @Min(value = 1, message = "条数不能小于 1") Integer limit) {

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
