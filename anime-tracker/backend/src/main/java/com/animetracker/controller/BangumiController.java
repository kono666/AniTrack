package com.animetracker.controller;

import com.animetracker.config.SubjectExtrasRateLimiter;
import com.animetracker.dto.ApiResponse;
import com.animetracker.dto.BangumiDTO.CalendarDay;
import com.animetracker.dto.response.AnimeDTO;
import com.animetracker.dto.response.AnimeMapper;
import com.animetracker.dto.response.EpisodeDTO;
import com.animetracker.dto.response.SubjectExtrasDTO;
import com.animetracker.entity.Anime;
import com.animetracker.entity.Episode;
import com.animetracker.entity.SubjectRelation;
import com.animetracker.entity.SubjectStaff;
import com.animetracker.service.AnimeService;
import com.animetracker.service.SubjectExtrasService;
import com.animetracker.util.TagTranslationUtil;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
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
    private final SubjectExtrasService subjectExtrasService;
    private final SubjectExtrasRateLimiter rateLimiter;

    public BangumiController(AnimeService animeService,
                             AnimeMapper animeMapper,
                             SubjectExtrasService subjectExtrasService,
                             SubjectExtrasRateLimiter rateLimiter) {
        this.animeService = animeService;
        this.animeMapper = animeMapper;
        this.subjectExtrasService = subjectExtrasService;
        this.rateLimiter = rateLimiter;
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

    // ══════════ 附属数据: 角色 / 制作人员 / 关联条目 ══════════

    /**
     * 角色与声优.
     *
     * <p>这三个接口(含下面两个)与详情页那一条的其余请求是<b>并行</b>发的, 它们各自独立,
     * 前端也是分开渲染、分开重试的(见 {@code AnimeDetail.vue} 的 {@code loadExtras})。
     *
     * <p><b>为什么取不到时回 502 而不是 200 + 空数组。</b> 空数组有一个确定的含义 ——
     * 「这个条目确实没有角色」(实测 subject 21 就是), 而它应当让那一整块<b>静默隐藏</b>。
     * 把"上游抖了一下"也编码成空数组, 用户看到的就是那块内容无声无息地消失: 页面其余
     * 部分完好, 没有报错, 没有可点的东西 —— 他只会以为这部番没收录角色。
     * 所以两种情况在协议层就分开, 前端才有办法分别渲染"隐藏"与"加载失败 · 重试"。
     *
     * <p>回 502 的条件很窄: <b>这次回源失败了, 且库里也没有上一版数据能顶上来</b>。
     * 有陈旧数据时照常回 200 —— 陈旧的角色表比一个错误提示有用。
     */
    @GetMapping("/subject/{subjectId}/characters")
    public ResponseEntity<ApiResponse<List<SubjectExtrasDTO.CharacterDTO>>> getCharacters(
            @PathVariable @Min(value = 1, message = "条目 id 必须大于 0") Integer subjectId,
            HttpServletRequest http) {

        rateLimiter.check(http.getRemoteAddr());
        SubjectExtrasService.SectionResult<SubjectExtrasService.CharacterBlock> result =
                subjectExtrasService.getCharacters(subjectId);
        if (result.failed()) {
            return upstreamUnavailable();
        }
        List<SubjectExtrasDTO.CharacterDTO> list = result.data().characters().stream()
                .map(c -> SubjectExtrasDTO.CharacterDTO.from(c, result.data().actorsOf(c.getCharacterId())))
                .collect(Collectors.toList());
        return ResponseEntity.ok(ApiResponse.success(list));
    }

    /** 制作人员 */
    @GetMapping("/subject/{subjectId}/staff")
    public ResponseEntity<ApiResponse<List<SubjectExtrasDTO.StaffDTO>>> getStaff(
            @PathVariable @Min(value = 1, message = "条目 id 必须大于 0") Integer subjectId,
            HttpServletRequest http) {

        rateLimiter.check(http.getRemoteAddr());
        SubjectExtrasService.SectionResult<List<SubjectStaff>> result =
                subjectExtrasService.getStaff(subjectId);
        if (result.failed()) {
            return upstreamUnavailable();
        }
        return ResponseEntity.ok(ApiResponse.success(
                result.data().stream().map(SubjectExtrasDTO.StaffDTO::from).collect(Collectors.toList())));
    }

    /**
     * 关联条目(前传 / 续集 / 剧场版 / 游戏 / 画集 …).
     *
     * <p>与"相关推荐"那一块不是一回事: 那一块是<b>按第一个标签</b>筛出来的同类型条目
     * (一个猜测), 这一块是上游明明白白标着关系的条目(一个事实)。所以它们在页面上是
     * 两块, 标题也不一样。
     */
    @GetMapping("/subject/{subjectId}/relations")
    public ResponseEntity<ApiResponse<List<SubjectExtrasDTO.RelationDTO>>> getRelations(
            @PathVariable @Min(value = 1, message = "条目 id 必须大于 0") Integer subjectId,
            HttpServletRequest http) {

        rateLimiter.check(http.getRemoteAddr());
        SubjectExtrasService.SectionResult<List<SubjectRelation>> result =
                subjectExtrasService.getRelations(subjectId);
        if (result.failed()) {
            return upstreamUnavailable();
        }
        return ResponseEntity.ok(ApiResponse.success(
                result.data().stream().map(SubjectExtrasDTO.RelationDTO::from).collect(Collectors.toList())));
    }

    /**
     * 三处共用的"上游取不到"响应.
     *
     * <p>HTTP 状态与响应体里的 {@code code} 都给 502: 只给一个的话, 读日志的人与写前端的人
     * 会各自看到一半 —— 而这一版前端是靠 axios 的 reject(真状态码)判断的,
     * 它不看 {code}, 所以那个字段在这里是给日志和以后的人看的。
     */
    private <T> ResponseEntity<ApiResponse<T>> upstreamUnavailable() {
        return ResponseEntity.status(HttpStatus.BAD_GATEWAY)
                .body(ApiResponse.error(HttpStatus.BAD_GATEWAY.value(),
                        "上游暂时取不到, 请稍后重试"));
    }

    // ══════════ 排行榜 ══════════

    /**
     * 排行榜 / 最近更新.
     *
     * <p>{@code limit} 的上界 200 是<b>跟随前端现状</b>定的, 不是推导出来的:
     * {@code Home.vue} 的"新番时间表"取的就是 {@code getRanking('date', 200)}.
     * 在下推分页之前这里只封了下界, {@code ?limit=999999} 会把整张表倒出来
     * (公开 GET, 不需要登录); 现在读的行数封在这一页上, 但响应体与实例化仍然
     * 与 limit 同阶, 所以上界还是要有. 要调大它, 先看前端是不是真的需要.
     *
     * <p>{@code sort=date} 走的是"最近更新", 序与 {@code sort=rank} 不同
     * (播出日倒序 vs 加权评分倒序), 但两者读的行数都封在 limit 上.
     */
    @GetMapping("/ranking")
    public ApiResponse<List<AnimeDTO>> getRanking(
            @RequestParam(defaultValue = "rank") String sort,
            @RequestParam(defaultValue = "20")
            @Min(value = 1, message = "条数不能小于 1")
            @Max(value = 200, message = "条数不能大于 200") Integer limit) {

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

    /**
     * 按标签取番剧, 最多 {@value AnimeService#BY_TAG_LIMIT} 条.
     *
     * <p>为什么是封顶而不是分页: 这个接口的响应体是**一个裸数组**, 前端的
     * AnimeDetail.vue 直接读 {@code res.data.data} 取相关番剧, 首页 Home.vue 也把它
     * 当成数组自己 slice 出 24 条一页、并拿数组长度当总页数. 换成
     * {@code {list,total,page}} 会让这两处悄无声息地渲染成空 —— 没有编译期信号.
     * 真正的分页留给批次 6 与前端一起改.
     *
     * <p>现在这个上限带来一个可见的变化: 标签下超过 50 部时, 首页标签浏览最多翻到
     * 第 3 页(50 / 24). 这是"不封顶地一次倒出全部"的必要代价 —— 那个洞在数据长起来
     * 之后就是公开接口上的一个任意大响应.
     */
    @GetMapping("/by-tag")
    public ApiResponse<List<AnimeDTO>> getByTag(@RequestParam String tag) {
        Set<String> enTags = TagTranslationUtil.reverseTranslateAll(tag);
        return ApiResponse.success(
                animeMapper.toListItems(animeService.getByTags(enTags, AnimeService.BY_TAG_LIMIT)));
    }

    // ══════════ 筛选 ══════════

    @GetMapping("/filter-meta")
    public ApiResponse<Map<String, Object>> getFilterMeta() {
        return ApiResponse.success(animeService.getFilterMeta());
    }

    /**
     * 按年份 / 季度 / 状态 / 四组标签筛选, 分页返回.
     *
     * <p>返回结构从「一个裸数组」改成了 {@code {list, total, page}} —— 与
     * {@code /api/bangumi/search} 完全一致, 前端 Search.vue 读的就是这个形状
     * (批次 6 的筛选页分页会直接复用它). 改之前这里一次返回全部匹配行,
     * 无参数时即整张表.
     *
     * <p><b>四个标签参数都是"逗号分隔的标签名", 组内 OR、组间 AND.</b>
     * 参数名从单个的 {@code tag} 换成了 {@code genre / medium / source / region},
     * 且**不留 {@code tag} 别名** —— 留一个同义参数会让"同一个筛选有两种表达"长期
     * 存在, 而唯一的前端调用方只有一处(详情页的相关推荐), 改名是一行.
     *
     * <p><b>为什么是逗号而不是重复参数({@code ?genre=a&genre=b}).</b> 走线的是
     * 标签名, 一个选项常常展开成好几个名字(「机甲」→ 机战/萝卜/机甲/机器人),
     * 重复参数会让 URL 长到没人愿意读; 而当前库里 40,247 个标签**一个都不含逗号**
     * (2026-09-30 只读实测), 分隔符与名字不会撞车. 这个前提与撞车时的行为写在
     * {@link com.animetracker.service.AnimeService.FilterQuery#fromCsv}.
     *
     * <p><b>{@code season} 同时接受 {@code yyyy-MM} 与 {@code yyyy-Qn}.</b> 前者是
     * 库里 {@code anime.season} 的原样(某一个月), 后者展开成整个季度
     * ({@code 2024-Q4} → 10/11/12 月)—— 不展开的话「2024 年秋季」只能筛出十月,
     * 而十一月十二月会静默漏掉. 认不出的值仍然是"筛空", 不是 400: 那是这个参数
     * 一直以来的行为, 改它会打断既有调用方. 详见
     * {@link com.animetracker.util.SeasonRange}.
     *
     * <p>page / limit 的注解与 search 那边同一套: 少了类级 {@code @Validated}
     * 它们会被静默忽略(见类注释), 越界的 page 则是 (page-1)*limit 溢出/负起点
     * 那条老路. 下界用 @Min 封, service 里再夹一道, 两层都留着.
     */
    @SuppressWarnings("unchecked")
    @GetMapping("/filter")
    public ApiResponse<Map<String, Object>> getFiltered(
            @RequestParam(required = false) String year,
            @RequestParam(required = false) String season,
            @RequestParam(required = false) String status,
            @RequestParam(required = false) String genre,
            @RequestParam(required = false) String medium,
            @RequestParam(required = false) String source,
            @RequestParam(required = false) String region,
            @RequestParam(defaultValue = "rank") String sort,
            @RequestParam(defaultValue = "1")
            @Min(value = 1, message = "页码从 1 开始") Integer page,
            @RequestParam(defaultValue = "20")
            @Min(value = 1, message = "每页条数不能小于 1")
            @Max(value = 50, message = "每页条数不能超过 50") Integer limit) {

        Map<String, Object> result = animeService.getFilteredPage(
                AnimeService.FilterQuery.fromCsv(year, season, status, genre, medium, source, region),
                sort, page, limit);
        List<Anime> list = (List<Anime>) result.get("list");
        int total = ((Number) result.getOrDefault("total", 0)).intValue();

        Map<String, Object> body = Map.of(
                "list", animeMapper.toListItems(list),
                "total", total,
                // 报告 service 夹过之后的页码: 越界请求拿到的是第 1 页,
                // 这里如实回一页, 而不是把请求里那个越界值原样回显
                "page", result.get("page"));
        return ApiResponse.success(body);
    }
}
