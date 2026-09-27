package com.animetracker.agent.tool;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 从工具返回值里挑出番剧条目, 供前端在对话流里渲染成卡片.
 *
 * 为什么要有这一步, 而不是直接把工具结果整个丢给前端:
 *   工具返回值是<b>给模型看的</b> —— 字段按模型判断的需要裁剪, 还会被截断到几千字符.
 *   前端要的是<b>给人看的</b>一小撮字段. 两者混用, 要么前端拿到一堆没用的字段,
 *   要么为了前端把给模型看的载荷撑大, 两边都不讨好.
 * 这里只做一次转换: 从结果里认出番剧, 压成前端画卡片真正需要的几个字段.
 *
 * 认的凭据是 {@link ToolViews#TYPE_KEY} 这个显式标记, 不是「长得像不像番剧」.
 * 后者试过, 不行: {@code EpisodeDTO} 同样有 id 和 name, 剧集列表会被画成一排
 * 「第 1 话」的卡片. 显式标记多花十几个字符, 换来确定性.
 */
public final class ToolCards {

    /** 一次最多画几张卡片: 超过之后用户也不会往下看, 白白撑大 SSE 载荷 */
    private static final int MAX_CARDS = 6;

    /** 简介在卡片里只做一行的引子 */
    private static final int SUMMARY_CHARS = 60;

    private ToolCards() {
    }

    /**
     * 从任意工具返回值里提取番剧卡片.
     *
     * @param toolResult 工具执行后返回的原始对象 (未序列化)
     * @return 卡片列表, 没有可识别的番剧时返回空列表
     */
    public static List<Map<String, Object>> extract(Object toolResult) {
        List<Map<String, Object>> out = new ArrayList<>();
        collect(toolResult, out, 0);
        return out.size() > MAX_CARDS ? new ArrayList<>(out.subList(0, MAX_CARDS)) : out;
    }

    /**
     * 往下找一层.
     *
     * 只递归一层是刻意的: 工具返回的结构都是「一层包装 + 一个数组」
     * (如 {@code {subjectId, count, reviews}}), 再深就是正文数据了, 没有番剧.
     */
    private static void collect(Object node, List<Map<String, Object>> out, int depth) {
        if (depth > 1 || out.size() >= MAX_CARDS) {
            return;
        }
        if (node instanceof List<?> list) {
            for (Object item : list) {
                if (out.size() >= MAX_CARDS) {
                    return;
                }
                if (item instanceof Map<?, ?> map) {
                    addIfAnime(map, out);
                }
            }
            return;
        }
        if (node instanceof Map<?, ?> map) {
            // 自己就是一条番剧 (get_anime_detail 这种返回单个对象的工具)
            if (addIfAnime(map, out)) {
                return;
            }
            // 否则往下看它的每个值, 找出装在里面的数组
            for (Object value : map.values()) {
                collect(value, out, depth + 1);
            }
        }
    }

    /** @return 这条记录是否被认成番剧 */
    private static boolean addIfAnime(Map<?, ?> map, List<Map<String, Object>> out) {
        if (!ToolViews.TYPE_ANIME.equals(map.get(ToolViews.TYPE_KEY))) {
            return false;
        }
        Integer id = asInt(map.get("id"));
        String name = asText(map.get("name"));
        if (id == null || name == null) {
            // 标记在但字段缺: 数据有问题, 宁可不画也不能画出一张空卡片
            return false;
        }
        // 同一个番剧可能被多个字段重复引用 (比如详情里嵌了相似推荐), 去重
        for (Map<String, Object> existing : out) {
            if (id.equals(existing.get("id"))) {
                return true;
            }
        }
        Map<String, Object> card = new LinkedHashMap<>();
        card.put("id", id);
        card.put("name", name);
        // 缺的字段直接不放, 而不是塞一个 null: 这条载荷走 SSE 推给浏览器,
        // 每个 null 都是白发的字节, 前端还得逐个判空
        putIfPresent(card, "nameCn", asText(map.get("nameCn")));
        putIfPresent(card, "cover", asText(map.get("cover")));
        putIfPresent(card, "rating", asDouble(map.get("rating")));
        putIfPresent(card, "date", asText(map.get("date")));
        putIfPresent(card, "episodes", asInt(map.get("episodes")));
        putIfPresent(card, "trackingCount", asInt(map.get("trackingCount")));
        String summary = asText(map.get("summary"));
        if (summary != null) {
            card.put("summary", summary.length() <= SUMMARY_CHARS
                    ? summary
                    : summary.substring(0, SUMMARY_CHARS) + "…");
        }
        out.add(card);
        return true;
    }

    private static void putIfPresent(Map<String, Object> card, String key, Object value) {
        if (value != null) {
            card.put(key, value);
        }
    }

    private static Integer asInt(Object v) {
        if (v instanceof Number n) {
            return n.intValue();
        }
        if (v instanceof String s) {
            try {
                return Integer.parseInt(s.trim());
            } catch (NumberFormatException e) {
                return null;
            }
        }
        return null;
    }

    private static Double asDouble(Object v) {
        if (v instanceof Number n) {
            return n.doubleValue();
        }
        return null;
    }

    private static String asText(Object v) {
        if (!(v instanceof String s)) {
            return null;
        }
        String t = s.trim();
        return t.isEmpty() ? null : t;
    }
}
