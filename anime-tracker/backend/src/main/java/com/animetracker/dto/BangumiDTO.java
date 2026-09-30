package com.animetracker.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.databind.JsonNode;
import lombok.Data;
import java.util.List;
import java.util.Map;

public class BangumiDTO {

    @Data
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class SubjectDTO {
        private Integer id;
        private String name;
        @JsonProperty("name_cn")
        private String nameCn;
        private String summary;
        private ImagesDTO images;
        private RatingDTO rating;
        private String date;
        private String platform;
        @JsonProperty("total_episodes")
        private Integer totalEpisodes;
        private List<TagDTO> tags;
        private String type;       // 2=anime
        private List<InfoboxItem> infobox;
    }

    /**
     * infobox 的一项, 形如 {@code {"key":"别名","value":[...]}}.
     *
     * <p>为什么要收这个: 罗马音/英文/其它地区译名全在 key 为「别名」的那一项里
     * (见 {@link com.animetracker.util.AnimeAliases}), 而搜索此前只匹配日文原名与中文名.
     * 这个字段被 {@code @JsonIgnoreProperties(ignoreUnknown = true)} 静默丢了很久 ——
     * 不是"没数据可用", 是数据送到了门口没接.
     *
     * <p>value 为什么是 {@code JsonNode} 而不是 String 或 List: 它的形状**不固定**,
     * 同一个响应里既有 {@code "话数":"26"}(字符串)又有
     * {@code "别名":[{"v":"EVA"},...]}(对象数组). 形状只在
     * {@link com.animetracker.util.AnimeAliases} **一处**解释, 所以这里原样接住即可 ——
     * 与"DTO→实体的映射只留一处"是同一个理由: 散在两处就会有一处忘了跟上.
     */
    @Data
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class InfoboxItem {
        private String key;
        private JsonNode value;
    }

    @Data
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class ImagesDTO {
        private String large;
        private String common;
        private String medium;
    }

    @Data
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class RatingDTO {
        private Double score;
        private Integer total;
        private Integer rank;
    }

    @Data
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class TagDTO {
        private String name;
        private Integer count;
    }

    @Data
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class SearchResponse {
        private List<SubjectDTO> data;
        private Integer total;
    }

    @Data
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class EpisodeDTO {
        private Long id;
        private Integer type;      // 0=本篇
        @JsonProperty("sort")
        private Double ep;         // 剧集序号
        private String name;
        @JsonProperty("name_cn")
        private String nameCn;
        private String airdate;
        private String duration;
    }

    @Data
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class EpisodeListResponse {
        private List<EpisodeDTO> data;
        private Integer total;
    }

    @Data
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class CalendarItem {
        private Integer id;
        private String name;
        @JsonProperty("name_cn")
        private String nameCn;
        private ImagesDTO images;
        private RatingDTO rating;
        private Integer rank;
        @JsonProperty("air_date")
        private String airDate;
    }

    @Data
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class CalendarDay {
        /**
         * 接口实际返回的形状: {@code {en:"Mon", cn:"星期一", ja:"月曜日", id:1}},
         * id 从 1(周一) 到 7(周日).
         *
         * <p><b>注意 id 在 API 那边是数字, 而这里声明成 {@code Map<String,String>}</b>
         * —— Jackson 会把它强制转成字符串, 前端拿到的因此是 {@code "1"} 而不是 {@code 1}.
         * Home.vue 的星期比对依赖这一点(两边都 String() 归一).
         *
         * <p>改前这条注释写的是 {@code cn:"周一"}, 与真实返回值不符(是"星期一"),
         * 而且漏了 id —— 前端照着它在错的方向上比对(拿"周三"比"星期三"), 区块整个
         * 不显示, 却没有任何测试会红. 一条写错的注释比没有注释更贵.
         */
        private Map<String, String> weekday;
        private List<CalendarItem> items;
    }

    @Data
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class SearchRequest {
        private String keyword;
        private String sort = "rank";
        private Map<String, List<Integer>> filter;  // e.g. {"type": [2]}
        private Integer offset = 0;
        private Integer limit = 20;

        public void setTypeFilter(Integer type) {
            this.filter = Map.of("type", List.of(type));
        }
    }
}
