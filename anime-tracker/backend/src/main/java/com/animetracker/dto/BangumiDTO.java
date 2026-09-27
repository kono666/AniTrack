package com.animetracker.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
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
        private Map<String, String> weekday;  // {en: "Monday", cn: "周一", ja: "月曜日"}
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
