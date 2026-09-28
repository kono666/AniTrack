package com.animetracker.entity;

import jakarta.persistence.*;
import lombok.*;
import java.time.LocalDateTime;

/**
 * 已看剧集: 一个用户对某番的某一集只会有一条. 约束说明同 {@link AnimeTracking}.
 */
@Entity
@Table(name = "episode_watched",
        uniqueConstraints = @UniqueConstraint(
                name = "uk_episode_watched_user_anime_episode",
                columnNames = {"user_id", "anime_id", "episode_num"}))
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class EpisodeWatched {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "user_id", nullable = false)
    private User user;

    @Column(name = "anime_id", nullable = false)
    private Integer animeId;

    @Column(name = "episode_num", nullable = false)
    private Integer episodeNum;

    @Column(updatable = false)
    private LocalDateTime watchedAt;

    @PrePersist
    protected void onCreate() {
        watchedAt = LocalDateTime.now();
    }
}
