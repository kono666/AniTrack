package com.animetracker.agent.tool;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 卡片提取的两个方向都要钉住:
 * 该认出来的没认出来, 表现是「明明查到了却只有一段 JSON」;
 * 不该认的认出来了, 表现是评论区冒出一排莫名其妙的卡片.
 * 后者的代价更大 —— 前者只是少了个点缀, 后者看起来像坏了.
 */
class ToolCardsTest {

    /** 与 ToolViews.animeBrief 保持一致的形状 (含类型标记) */
    private static Map<String, Object> brief(int id, String name) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put(ToolViews.TYPE_KEY, ToolViews.TYPE_ANIME);
        m.put("id", id);
        m.put("name", name);
        m.put("nameCn", "中文名" + id);
        m.put("date", "2024-01-0" + id);
        m.put("episodes", 12);
        m.put("rating", 8.5);
        m.put("cover", "https://example.com/" + id + ".jpg");
        m.put("tags", List.of("热血", "奇幻"));
        return m;
    }

    @Test
    @DisplayName("搜索结果: 从 list 字段里把番剧挑出来")
    void extractsFromSearchResult() {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("keyword", "巨人");
        result.put("total", 2);
        result.put("list", List.of(brief(1, "进击的巨人"), brief(2, "进击的巨人 最终季")));

        List<Map<String, Object>> cards = ToolCards.extract(result);

        assertThat(cards).hasSize(2);
        assertThat(cards.get(0))
                .containsEntry("id", 1)
                .containsEntry("name", "进击的巨人")
                .containsEntry("nameCn", "中文名1")
                .containsEntry("cover", "https://example.com/1.jpg")
                .containsEntry("rating", 8.5)
                .containsEntry("episodes", 12);
        // tags 是给模型判断用的, 卡片不需要
        assertThat(cards.get(0)).doesNotContainKey("tags");
        // 标记是内部约定, 不该出现在给前端的载荷里
        assertThat(cards.get(0)).doesNotContainKey(ToolViews.TYPE_KEY);
    }

    @Test
    @DisplayName("详情: 工具直接返回单个番剧时也认")
    void extractsSingleAnimeDetail() {
        Map<String, Object> detail = new LinkedHashMap<>(brief(9, "葬送的芙莉莲"));
        detail.put("summary", "魔王讨伐之后的故事");
        detail.put("ratingCount", 1024);

        List<Map<String, Object>> cards = ToolCards.extract(detail);

        assertThat(cards).hasSize(1);
        assertThat(cards.get(0)).containsEntry("id", 9).containsEntry("summary", "魔王讨伐之后的故事");
    }

    @Test
    @DisplayName("运营热度榜: 打了标记的榜单行也能出卡片")
    void extractsTaggedHeatRankingRows() {
        // AdminService 用的是库表里的叫法, 由 ToolViews.tagAnimeRows 统一成 id 并打标记
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("subjectId", 4242);
        row.put("name", "某部热门番");
        row.put("trackingCount", 318);
        row.put("rating", 9.1);
        ToolViews.tagAnimeRows(List.of(row));

        List<Map<String, Object>> cards = ToolCards.extract(Map.of("ranking", List.of(row)));

        assertThat(cards).hasSize(1);
        assertThat(cards.get(0))
                .containsEntry("id", 4242)
                .containsEntry("trackingCount", 318);
    }

    @Test
    @DisplayName("剧集列表不会被误认成番剧")
    void doesNotMistakeEpisodesForAnime() {
        // 这就是当初放弃「猜形状」的原因: EpisodeDTO 同样有 id 和 name,
        // 靠形状判断会把剧集画成一排「第 N 话」的卡片
        Map<String, Object> episode = new LinkedHashMap<>();
        episode.put("id", 1);
        episode.put("sort", 1);
        episode.put("name", "第 1 话");
        episode.put("nameCn", "第 1 话");

        List<Map<String, Object>> cards = ToolCards.extract(
                Map.of("subjectId", 100, "count", 1, "episodes", List.of(episode)));

        assertThat(cards).isEmpty();
    }

    @Test
    @DisplayName("评论列表不会被误认成番剧")
    void doesNotMistakeReviewsForAnime() {
        Map<String, Object> review = new LinkedHashMap<>();
        review.put("id", 1);
        review.put("username", "路人甲");
        review.put("content", "很好看");
        review.put("score", 9);

        List<Map<String, Object>> cards = ToolCards.extract(
                Map.of("subjectId", 100, "count", 1, "reviews", List.of(review)));

        assertThat(cards).isEmpty();
    }

    @Test
    @DisplayName("同一部番重复出现只画一张卡片")
    void deDuplicates() {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("list", List.of(brief(7, "同名番")));
        result.put("recommend", List.of(brief(7, "同名番")));

        assertThat(ToolCards.extract(result)).hasSize(1);
    }

    @Test
    @DisplayName("最多 6 张: 再多用户也不会往下看, 只是白白撑大 SSE 载荷")
    void capsCardCount() {
        List<Map<String, Object>> list = new ArrayList<>();
        for (int i = 1; i <= 20; i++) {
            list.add(brief(i, "番剧" + i));
        }

        List<Map<String, Object>> cards = ToolCards.extract(Map.of("list", list));

        assertThat(cards).hasSize(6);
        // 保留的是最靠前的几条, 而不是随机截断
        assertThat(cards.get(0)).containsEntry("id", 1);
        assertThat(cards.get(5)).containsEntry("id", 6);
    }

    @Test
    @DisplayName("简介过长时截断, 卡片只是一行引子")
    void truncatesLongSummary() {
        Map<String, Object> detail = new LinkedHashMap<>(brief(3, "长简介番"));
        detail.put("summary", "很".repeat(200));

        String summary = (String) ToolCards.extract(detail).get(0).get("summary");

        assertThat(summary).hasSize(61).endsWith("…");
    }

    @Test
    @DisplayName("各种没结果/畸形输入一律返回空列表, 不抛异常")
    void toleratesUnexpectedInput() {
        assertThat(ToolCards.extract(null)).isEmpty();
        assertThat(ToolCards.extract("纯字符串结果")).isEmpty();
        assertThat(ToolCards.extract(List.of("a", "b"))).isEmpty();
        assertThat(ToolCards.extract(Map.of("count", 0))).isEmpty();
        // 有标记但字段缺失: 画不出来也不能出错, 更不能画一张空卡片
        Map<String, Object> noName = new LinkedHashMap<>();
        noName.put(ToolViews.TYPE_KEY, ToolViews.TYPE_ANIME);
        noName.put("id", 5);
        assertThat(ToolCards.extract(noName)).isEmpty();
        // id 不是数字
        Map<String, Object> badId = new LinkedHashMap<>();
        badId.put(ToolViews.TYPE_KEY, ToolViews.TYPE_ANIME);
        badId.put("id", "abc");
        badId.put("name", "x");
        assertThat(ToolCards.extract(badId)).isEmpty();
    }

    @Test
    @DisplayName("空白字符串当成缺失, 缺的字段不放进卡片")
    void treatsBlankAsMissing() {
        Map<String, Object> m = brief(5, "  某番  ");
        m.put("nameCn", "   ");
        m.put("cover", "");
        m.put("date", null);

        Map<String, Object> card = ToolCards.extract(m).get(0);

        assertThat(card).containsEntry("name", "某番");
        // 是「没有这个键」而不是「键在但值为 null」: 后者会白占 SSE 的字节,
        // 前端还得逐个判空
        assertThat(card).doesNotContainKey("nameCn");
        assertThat(card).doesNotContainKey("cover");
        assertThat(card).doesNotContainKey("date");
    }

    @Test
    @DisplayName("一层包装之后的嵌套数组仍能找到 (真实工具的返回形状)")
    void walksOneLevelDeep() {
        // get_anime_list_by_tag 的返回: {tag, count, list:[...]}
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("tag", "治愈");
        result.put("count", 1);
        result.put("list", List.of(brief(11, "夏目友人帐")));

        assertThat(ToolCards.extract(result)).hasSize(1);
    }

    @Test
    @DisplayName("打了标记的行缺 subjectId 时不补 id, 也不当成番剧")
    void taggingLeavesRowsWithoutIdAlone() {
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("name", "没有 id 的行");
        ToolViews.tagAnimeRows(List.of(row));

        assertThat(row).doesNotContainKey("id");
        assertThat(ToolCards.extract(Map.of("list", List.of(row)))).isEmpty();
    }

    @Test
    @DisplayName("tagAnimeRows 容忍 null 列表与 null 行")
    void taggingToleratesNulls() {
        ToolViews.tagAnimeRows(null);
        List<Map<String, Object>> withNull = new ArrayList<>();
        withNull.add(null);
        ToolViews.tagAnimeRows(withNull);
        assertThat(withNull).hasSize(1);
    }
}
