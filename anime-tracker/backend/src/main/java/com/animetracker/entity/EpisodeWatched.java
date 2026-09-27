package com.animetracker.entity;

import jakarta.persistence.*;
import lombok.*;
import java.time.LocalDateTime;

@Entity
@Table(name = "episode_watched")
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
