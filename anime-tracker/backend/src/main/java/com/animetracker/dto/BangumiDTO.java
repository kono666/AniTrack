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

    /**
     * 上游的图片变体.
     *
     * <p><b>{@code grid} 与 {@code small} 是 V17 补的</b>, 在它们之前这里只有
     * large/common/medium. 补的理由不是"多接一个字段不花钱", 而是三个 extras 接口
     * (characters/persons/subjects)**根本没有 common**, 而角色与人员那一侧最优的档位
     * 恰恰是 grid —— 实测 (六部番 226 个角色 / 325 个人员):
     *
     * <ul>
     *   <li>{@code characters}: grid 与 large 的通过率相同(92.9%), 而 grid 的体积是
     *       large 的四分之一左右;</li>
     *   <li>{@code persons}: 同样 grid 与 large 并列(60.9%), 其余档位一个都不通过;</li>
     *   <li>{@code subjects}(关联条目): <b>只有 large 通过</b>, 它的 grid/common/medium
     *       全是 {@code /r/} 前缀那种形式(100% vs 0%)。</li>
     * </ul>
     *
     * <p>"通过率"指能过 {@link com.animetracker.util.CoverImages#canonical} 的
     * (https + lain.bgm.tv + {@code /pic/} 前缀 + 不带 query)。不通过的不会被丢掉,
     * 它们由 {@code proxied()} 原样返回、前端直连 —— 与存量封面同一条路子。
     */
    @Data
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class ImagesDTO {
        private String large;
        private String common;
        private String medium;
        private String grid;
        private String small;
    }

    /**
     * {@code GET /v0/subjects/{id}/characters} 的一项.
     *
     * <p>实测元素形状: {@code {actors:[...], id, images, name, relation, summary, type}}.
     * {@code summary} 刻意<b>不接</b>: 实测最长 1199 字符, 而详情页的角色卡只印名字、
     * 定位与声优, 收进来就是一列没人读的长文本。{@code type}(1~4)同理不接。
     *
     * <p>{@code actors} 可以为空 —— 实测 128 条角色里 63 条没有声优, 那不是异常数据,
     * 是"这个角色没有配音信息"。
     */
    @Data
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class CharacterDTO {
        private Integer id;
        private String name;
        /** 角色定位: 主角 / 配角 / 闲角 / 旁白 (实测最长 2 字) */
        private String relation;
        private ImagesDTO images;
        private List<ActorDTO> actors;
    }

    /**
     * 角色条目里的一个声优.
     *
     * <p>实测键: {@code {career, id, images, locked, name, short_summary, type}} ——
     * <b>没有 {@code relation}</b>(实测 0 条有)。所以声优这一侧没有"职务"可存,
     * 而同一个 {@code id} 会在同一条目里重复出现(实测最多 4 次): 同一个人配了不同角色。
     * 这正是 {@code subject_character_actor} 必须用代理主键的原因, 详见 V17 的注释。
     */
    @Data
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class ActorDTO {
        private Integer id;
        private String name;
        private ImagesDTO images;
    }

    /**
     * {@code GET /v0/subjects/{id}/persons} 的一项(我们叫它 staff 那一块).
     *
     * <p>实测键: {@code {career, eps, id, images, name, relation, type}}。
     * {@code career}(职业列表)与 {@code eps}(参与的集数, 实测是<b>字符串</b>)都不接 ——
     * 界面上那一格印的是"谁 + 干了什么"(name + relation), 这两项没有位置。
     *
     * <p>⚠️ 与角色那一侧不同, 这里 {@code relation} 是**有值的**(原画 / 作画监督 / 演出…,
     * 实测最长 8 字), 而且同一 {@code id} 会以不同 relation 反复出现(实测最多 5 次),
     * 所以它同样不能做唯一键。
     */
    @Data
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class PersonDTO {
        private Integer id;
        private String name;
        private String relation;
        private ImagesDTO images;
    }

    /**
     * {@code GET /v0/subjects/{id}/subjects} 的一项 —— 关联条目.
     *
     * <p>实测键: {@code {id, images, name, name_cn, relation, type}}。
     * <b>没有 {@code date}</b>(所以卡片上不印年份, 不是"我们没取", 是上游不给)。
     *
     * <p>{@code name_cn} 常常是空串而不是 null, 而且大量条目本来就没有中文名 ——
     * 选名字的规则见 {@code SubjectExtrasMapper}.
     */
    @Data
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class RelatedSubjectDTO {
        private Integer id;
        private String name;
        @JsonProperty("name_cn")
        private String nameCn;
        /** 前传 / 续集 / 剧场版 / 游戏 / 画集 … (实测最长 5 字) */
        private String relation;
        private ImagesDTO images;
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
