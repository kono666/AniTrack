package com.animetracker.agent.tool;

import com.animetracker.agent.tool.ToolDefinition.Access;
import com.animetracker.dto.response.EpisodeDTO;
import com.animetracker.entity.Anime;
import com.animetracker.service.AnimeService;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 内容发现类工具 —— 找番、看详情、看排行.
 *
 * 全部无需登录, 用户端与管理端共用.
 */
@Component
public class AnimeTools implements ToolProvider {

    private final AnimeService animeService;

    public AnimeTools(AnimeService animeService) {
        this.animeService = animeService;
    }

    @Override
    public List<ToolDefinition> tools() {
        return List.of(search(), detail(), episodes(), ranking(), latest(),
                calendar(), byTag(), filter(), tags());
    }

    private ToolDefinition search() {
        return ToolDefinition.builder()
                .name("search_anime")
                .description("按关键词搜索番剧。当用户提到某部具体番剧的名字，或想找某个主题的作品时用它。"
                        + "返回结果里每部番剧都带 id，后续查看详情或加入追番都要用这个 id。")
                .access(Access.PUBLIC)
                .stringParam("keyword", "搜索关键词，可以是番剧名或主题词，例如「进击的巨人」「机甲」", true)
                .intParam("limit", "返回条数，默认 8，最大 20", false)
                .executor((call, user) -> {
                    String keyword = call.str("keyword", "");
                    if (keyword.isBlank()) {
                        throw new IllegalArgumentException("关键词不能为空");
                    }
                    int limit = clamp(call.integer("limit", 8), 1, 20);
                    Map<String, Object> raw = animeService.searchAnime(keyword, 1, limit);

                    Map<String, Object> out = new LinkedHashMap<>();
                    out.put("keyword", keyword);
                    out.put("total", raw.get("total"));
                    out.put("list", ToolViews.animeBriefList(
                            ToolViews.extractAnimeList(raw.get("list"))));
                    return out;
                })
                .build();
    }

    private ToolDefinition detail() {
        return ToolDefinition.builder()
                .name("get_anime_detail")
                .description("查看某部番剧的详细信息，包含简介、评分、排名与标签。"
                        + "当用户想深入了解某一部作品，或你需要判断它是否适合推荐时使用。")
                .access(Access.PUBLIC)
                .intParam("subjectId", "番剧 id，来自搜索结果", true)
                .executor((call, user) -> {
                    Integer id = call.requireInteger("subjectId");
                    Anime anime = animeService.getAnimeDetail(id);
                    if (anime == null) {
                        throw new IllegalArgumentException("没有找到 id 为 " + id + " 的番剧");
                    }
                    return ToolViews.animeFull(anime);
                })
                .build();
    }

    private ToolDefinition episodes() {
        return ToolDefinition.builder()
                .name("get_episodes")
                .description("查看某部番剧的剧集列表（每集标题与播出日期）。"
                        + "用户问「这部有多少集」「某集什么时候播」时使用。")
                .access(Access.PUBLIC)
                .intParam("subjectId", "番剧 id", true)
                .executor((call, user) -> {
                    Integer id = call.requireInteger("subjectId");
                    List<EpisodeDTO> eps = animeService.getEpisodes(id).stream()
                            .map(EpisodeDTO::from)
                            .toList();
                    Map<String, Object> out = new LinkedHashMap<>();
                    out.put("subjectId", id);
                    out.put("count", eps.size());
                    out.put("episodes", eps);
                    return out;
                })
                .build();
    }

    private ToolDefinition ranking() {
        return ToolDefinition.builder()
                .name("get_ranking")
                .description("获取评分最高的番剧排行。用户想要「口碑最好的」「高分推荐」或让你推荐几部经典作品时使用。")
                .access(Access.PUBLIC)
                .intParam("limit", "返回条数，默认 10，最大 20", false)
                .executor((call, user) -> Map.of(
                        "list", ToolViews.animeBriefList(
                                animeService.getRanking(clamp(call.integer("limit", 10), 1, 20)))))
                .build();
    }

    private ToolDefinition latest() {
        return ToolDefinition.builder()
                .name("get_latest")
                .description("获取最近播出的新番。用户想找「最近有什么新番」「当季新作」时使用。")
                .access(Access.PUBLIC)
                .intParam("limit", "返回条数，默认 10，最大 20", false)
                .executor((call, user) -> Map.of(
                        "list", ToolViews.animeBriefList(
                                animeService.getLatest(clamp(call.integer("limit", 10), 1, 20)))))
                .build();
    }

    private ToolDefinition calendar() {
        return ToolDefinition.builder()
                .name("get_calendar")
                .description("获取每日放送表，按星期几分组。用户问「今天更新什么」「周三有什么番」时使用。")
                .access(Access.PUBLIC)
                .executor((call, user) -> animeService.getCalendar())
                .build();
    }

    private ToolDefinition byTag() {
        return ToolDefinition.builder()
                .name("get_by_tag")
                .description("按标签查找番剧。用户说想看某个类型（如「治愈」「悬疑」「运动」）时用它。"
                        + "最多返回 " + AnimeService.BY_TAG_LIMIT + " 部，按播出时间倒序。"
                        + "可先用 list_tags 确认标签的确切写法。")
                .access(Access.PUBLIC)
                .stringParam("tag", "标签名，例如 治愈 / 悬疑 / 科幻", true)
                .executor((call, user) -> {
                    String tag = call.str("tag", "");
                    if (tag.isBlank()) {
                        throw new IllegalArgumentException("标签不能为空");
                    }
                    // 与公开接口用同一个上限. 这里不是"少给几条"的问题: 工具结果
                    // 之后还会被 max-tool-result-chars 截断, 一次装配几百部番剧的
                    // 摘要再丢掉, 只是白白占着工作线程. count 因此是「本次返回的条数」.
                    List<Anime> list = animeService.getByTags(Set.of(tag), AnimeService.BY_TAG_LIMIT);
                    Map<String, Object> out = new LinkedHashMap<>();
                    out.put("tag", tag);
                    out.put("count", list.size());
                    out.put("list", ToolViews.animeBriefList(list));
                    if (list.isEmpty()) {
                        out.put("hint", "该标签下没有结果，可先用 list_tags 查看平台已有的标签写法");
                    }
                    return out;
                })
                .build();
    }

    private ToolDefinition filter() {
        return ToolDefinition.builder()
                .name("filter_anime")
                .description("按年份、季度、状态、标签多维筛选番剧。"
                        + "用户提出组合条件时用它，例如「2024年的完结番」「去年的悬疑番」。"
                        // 上限必须写给模型看. 不写的话它会以为 list 就是全集 ——
                        // 那正是 by-tag 当年踩过的坑(契约看着没变, 调用方却按全集理解).
                        + "一次最多返回 " + AnimeService.FILTER_TOOL_LIMIT + " 条；"
                        + "count 是符合条件的总数，count 大于实际返回的条数时说明还有更多，"
                        + "可以再加筛选条件(年份/季度/状态/标签)缩小范围。")
                .access(Access.PUBLIC)
                .stringParam("year", "年份，例如 2024。不限定则不传", false)
                .stringParam("season", "季度，格式为 yyyy-MM，例如 2024-10。不限定则不传", false)
                .enumParam("status", "播出状态。不限定则不传", false, "finished", "airing")
                .stringParam("tag", "标签名。不限定则不传", false)
                .enumParam("sort", "排序方式，默认按评分", false, "rating", "date", "rank")
                .executor((call, user) -> {
                    String year = blankToNull(call.str("year", null));
                    String season = blankToNull(call.str("season", null));
                    String status = blankToNull(call.str("status", null));
                    String tag = blankToNull(call.str("tag", null));
                    String sort = call.str("sort", "rating");

                    // 走分页版并封顶, 理由是省掉"读进来再丢掉"那一步
                    // (见 AnimeService.FILTER_TOOL_LIMIT). 注意 count 在这里是
                    // **真实匹配总数**, 与上面 get_by_tag 那个 count(本次返回的条数)
                    // 不是一个意思 —— 这里要能回答"是不是还有更多", 那边只需要回答
                    // "给了你几条".
                    Map<String, Object> page = animeService.getFilteredPage(
                            year, season, status, tag, sort, 1, AnimeService.FILTER_TOOL_LIMIT);
                    Map<String, Object> out = new LinkedHashMap<>();
                    out.put("count", page.get("total"));
                    out.put("list", ToolViews.animeBriefList(
                            ToolViews.extractAnimeList(page.get("list"))));
                    return out;
                })
                .build();
    }

    private ToolDefinition tags() {
        return ToolDefinition.builder()
                .name("list_tags")
                .description("列出平台上最热门的标签及各自的作品数量。"
                        + "当你不确定某个标签怎么写、或想给用户介绍平台支持哪些分类时用它。")
                .access(Access.PUBLIC)
                .executor((call, user) -> Map.of("tags", animeService.getAllTags()))
                .build();
    }

    private static int clamp(int value, int min, int max) {
        return Math.max(min, Math.min(max, value));
    }

    private static String blankToNull(String s) {
        return (s == null || s.isBlank()) ? null : s;
    }
}
