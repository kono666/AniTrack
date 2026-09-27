package com.animetracker.dto.response;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;
import java.util.Map;

/**
 * 番剧响应 DTO — 纯数据结构, 转换逻辑在 AnimeMapper 中.
 *
 * <p>列表接口返回简略版(无 summary/tags)，详情接口返回完整版。
 * 使用 Builder 构建, 字段与前端约定保持一致.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class AnimeDTO {
    private Integer id;
    private String name;             // 日文原名
    private String nameCn;           // 中文名
    private String summary;          // 简介(仅详情)
    private String date;             // 播出日期, 如 2024-01
    private String platform;         // 放送平台: TV/剧场版/OVA...
    private Integer totalEpisodes;
    private Map<String, String> images;    // {large, common, medium}
    private RatingInfo rating;             // 评分信息
    private CollectionInfo collection;     // 收藏状态分布
    private List<TagInfo> tags;            // 标签列表(仅详情)
    private Integer rank;                  // 排名(仅详情)

    // ── 嵌套类型 ────────────────────────────────────

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class RatingInfo {
        private Double score;
        private Integer total;
    }

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class CollectionInfo {
        private int wish;
        private int doing;
        private int collect;
        private int onHold;
        private int dropped;
    }

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class TagInfo {
        private String name;
        private int count;
    }
}
