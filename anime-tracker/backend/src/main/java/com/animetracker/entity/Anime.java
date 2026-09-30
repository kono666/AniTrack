package com.animetracker.entity;

import jakarta.persistence.*;
import lombok.*;
import java.time.LocalDateTime;

@Entity
@Table(name = "anime")
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class Anime {
    @Id
    private Integer id;                // Bangumi subject_id，不自增

    @Column(nullable = false, length = 200)
    private String title;            // 日文原名

    @Column(name = "title_cn", length = 200)
    private String titleCn;          // 中文名

    // Bangumi infobox 的「别名」, 逗号分隔(罗马音/英文/其它地区译名).
    // 存在是为了让搜索能匹配它们 —— 见 util/AnimeAliases 与 V5 迁移脚本.
    //
    // NULL 是有意义的业务状态: "这一行的别名还没补过". 详情页据此决定要不要回源一次
    // (AnimeService.getAnimeDetail). 所以这一列刻意没有 NOT NULL / 默认值.
    //
    // 长度必须与 V5 脚本里的 VARCHAR(1000) 逐字一致: ddl-auto=validate 拿列的类型与
    // 实体比对, 不一致会在**启动时**失败(开发库过、生产库起不来, 或反过来).
    @Column(length = 1000)
    private String aliases;          // 别名，逗号分隔

    @Column(columnDefinition = "TEXT")
    private String summary;          // 简介

    @Column(name = "cover_url", length = 500)
    private String coverUrl;         // 封面图URL

    @Column(length = 20)
    private String date;             // 播出日期 如 2024-01

    @Column(length = 50)
    private String platform;         // 放送平台

    @Column(name = "total_episodes")
    private Integer totalEpisodes;   // 总集数

    // 已完整取回的剧集条数 —— 与上面那个 totalEpisodes **不是一回事**, 别合并.
    //
    // totalEpisodes 来自条目接口自己的声明, 是"官方说有这么多集"; episodeTotal 是
    // "我们这边真的收齐了这么多条". 两者实测会不一致: 抽 28 部知名长篇, 有 9 部对不上,
    // 且方向恒为 totalEpisodes 更大 (乌龙派出所 373 vs 344, 犬夜叉 181 vs 167,
    // 食戟之灵 33 vs 24 ...). 差在哪儿不重要, 重要的是**不能拿声明去判断我们收齐没有** ——
    // 拿 181 当"应到"的话, 犬夜叉的 167 条永远够不着, 每次打开详情页都要重新下载一遍.
    //
    // 语义要分清, 因为判"缓存完整"全靠它:
    //   NULL -> 从来没完整取过 (改动前入库的老行全是 NULL, 也包含"只取过一页就存下"的行)
    //   0    -> 取过了, 确实没有本篇剧集 (SP/OVA 只有 OP/ED 的条目)
    //   n    -> 完整取过, 共 n 条
    // 新建条目时不填, 保持 NULL —— 让下一个人点开详情页时补一次完整抓取.
    //
    // 没有给它建索引: 唯一的问题是"这一行取齐了没有", 永远按主键取单行, 与索引无关.
    @Column(name = "episode_total")
    private Integer episodeTotal;    // 已完整取回的剧集条数 (NULL=没取过)

    // 精度与刻度写死在 columnDefinition 里, 而不是用 @Column(precision/scale):
    // 这里要的是一列定点小数, 写法必须与迁移脚本完全一致 (见 db/migration/[h2|postgres]/V1__init_schema.sql).
    //
    // 为什么是 NUMERIC 而不是 DECIMAL —— 这两个词在 SQL 里是同一个意思, 但两个数据库的
    // 驱动报出来的 JDBC 类型码并不一样, 而 Hibernate 的 validate 恰恰是拿类型码比对的:
    //   H2:          DECIMAL(3,1) -> Types#DECIMAL(3),  NUMERIC(3,1) -> Types#NUMERIC(2)
    //   PostgreSQL:  numeric(3,1) -> Types#NUMERIC(2)
    // 也就是说只有写成 NUMERIC, 两边才会都报 2. 这是实测出来的(Hibernate 6.3 + H2 2.2 + PG 16),
    // 换成 DECIMAL 会让开发库过、生产库直接起不来 —— 恰好是最难在本地发现的那类问题.
    @Column(columnDefinition = "NUMERIC(3,1)")
    private Double rating;           // 评分 1-10

    @Column(name = "rating_count")
    private Integer ratingCount;     // 评分人数

    @Column(length = 500)
    private String tags;             // 标签，逗号分隔

    @Column(length = 30)
    private String season;           // 季度 如 2024-01

    @Column(name = "sort_rank")
    private Integer rank;            // 排名

    @Column(length = 20)
    private String status;           // 状态: airing/finished

    private LocalDateTime cacheUpdatedAt;  // 缓存时间，用于过期判断
}
