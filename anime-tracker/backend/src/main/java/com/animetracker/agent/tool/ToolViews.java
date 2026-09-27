package com.animetracker.agent.tool;

import com.animetracker.entity.Anime;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 工具返回值的紧凑视图.
 *
 * 为什么不直接用现成的 AnimeDTO:
 *   AnimeDTO 是给前端渲染用的, 里面 images 是三个内容完全相同的 URL,
 *   collection 是五个恒为 0 的占位字段. 这些字段对模型判断毫无价值,
 *   但每次工具调用都要按 token 计费. 十条搜索结果能省下一半以上的 token.
 *
 * 另一个好处: 前端 DTO 改字段不会影响 Agent 的行为.
 */
public final class ToolViews {

    /**
     * 番剧条目的类型标记.
     *
     * 前端要按这个标记决定哪些结果能画成番剧卡片 (见 {@link ToolCards}).
     * 为什么不让前端靠「有没有 id 和 name」去猜:
     *   {@code EpisodeDTO} 恰好也有 id 和 name —— 剧集列表会被猜成番剧,
     *   画出一排「第 1 话」「第 2 话」的卡片. 猜形状的做法迟早会在某个新工具上翻车,
     *   明确写一个标记则一次到位, 代价是每条多十几个字符.
     */
    public static final String TYPE_KEY = "type";

    /** {@link #TYPE_KEY} 的取值 */
    public static final String TYPE_ANIME = "anime";

    private ToolViews() {
    }

    /** 列表场景的番剧表示 */
    public static Map<String, Object> animeBrief(Anime a) {
        if (a == null) {
            return Map.of();
        }
        Map<String, Object> m = new LinkedHashMap<>();
        m.put(TYPE_KEY, TYPE_ANIME);
        m.put("id", a.getId());
        m.put("name", a.getTitle());
        m.put("nameCn", a.getTitleCn());
        m.put("date", a.getDate());
        m.put("episodes", a.getTotalEpisodes());
        m.put("rating", a.getRating());
        // 只放一个封面地址, 不是 AnimeDTO 里那三个内容完全相同的 URL.
        // 这一条是为了前端的对话内联卡片: 光有番剧名, 用户还得自己再搜一次才能认出来是哪部.
        m.put("cover", a.getCoverUrl());
        m.put("tags", splitTags(a.getTags()));
        return m;
    }

    /** 详情场景的番剧表示, 额外带上简介 */
    public static Map<String, Object> animeFull(Anime a) {
        if (a == null) {
            return Map.of();
        }
        Map<String, Object> m = new LinkedHashMap<>(animeBrief(a));
        m.put("summary", trim(a.getSummary(), 400));
        m.put("ratingCount", a.getRatingCount());
        m.put("platform", a.getPlatform());
        m.put("rank", a.getRank());
        return m;
    }

    public static List<Map<String, Object>> animeBriefList(List<Anime> list) {
        List<Map<String, Object>> out = new ArrayList<>();
        if (list != null) {
            for (Anime a : list) {
                out.add(animeBrief(a));
            }
        }
        return out;
    }

    /** 从 searchAnime 返回的 Map 里安全取出 list 字段 */
    public static List<Anime> extractAnimeList(Object rawList) {
        if (!(rawList instanceof List<?> list)) {
            return List.of();
        }
        return list.stream()
                .filter(Anime.class::isInstance)
                .map(Anime.class::cast)
                .toList();
    }

    /**
     * 给不是由本类产出的番剧行补上类型标记 (运营端的热度榜).
     *
     * 标记在这里补而不是在 AdminService 里写死: 那是管理端的通用查询,
     * 不该反过来知道 AI 助手要怎么渲染它的结果. 谁来组装工具结果, 谁负责打标记.
     */
    public static void tagAnimeRows(List<Map<String, Object>> rows) {
        if (rows == null) {
            return;
        }
        for (Map<String, Object> row : rows) {
            if (row != null && row.get("id") == null && row.get("subjectId") != null) {
                // 榜单沿用了库表里的 subjectId 叫法, 统一成 id, 前端只认一种
                row.put("id", row.get("subjectId"));
            }
            if (row != null) {
                row.put(TYPE_KEY, TYPE_ANIME);
            }
        }
    }

    private static List<String> splitTags(String csv) {
        if (csv == null || csv.isBlank()) {
            return List.of();
        }
        return Arrays.stream(csv.split(","))
                .map(String::trim)
                .filter(s -> !s.isEmpty())
                .limit(8)
                .toList();
    }

    private static String trim(String s, int max) {
        if (s == null) {
            return null;
        }
        return s.length() <= max ? s : s.substring(0, max) + "...";
    }
}
