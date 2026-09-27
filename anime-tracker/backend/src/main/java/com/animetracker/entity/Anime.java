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

    @Column(columnDefinition = "DECIMAL(3,1)")
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
