package com.animetracker.entity;

import jakarta.persistence.*;
import lombok.*;
import java.time.LocalDateTime;

@Entity
@Table(name = "episode")
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class Episode {
    @Id
    private Long id;                   // Bangumi episode_id，不自增

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "anime_id", nullable = false)
    private Anime anime;

    @Column(name = "episode_num", nullable = false)
    private Integer episodeNum;      // 第几集

    @Column(length = 200)
    private String title;            // 剧集标题

    @Column(length = 20)
    private String airdate;          // 播出日期

    @Column(length = 10)
    private String duration;         // 时长 如 "24m"

    private LocalDateTime cacheUpdatedAt;  // 缓存时间
}
