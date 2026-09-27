package com.animetracker.entity;

import jakarta.persistence.*;
import lombok.*;
import java.time.LocalDateTime;

@Entity
@Table(name = "anime_tracking")
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class AnimeTracking {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "user_id", nullable = false)
    private User user;

    /** Bangumi subject_id */
    @Column(name = "subject_id", nullable = false)
    private Integer subjectId;

    /** 追番状态: want_to_watch / watching / watched / on_hold / dropped */
    @Column(nullable = false, length = 20)
    private String status;

    /** 观看进度(看到第几集) */
    @Column(nullable = false)
    @Builder.Default
    private Integer progress = 0;

    /** 个人评分 1-10 */
    private Integer score;

    /** 个人备注 */
    @Column(columnDefinition = "TEXT")
    private String notes;

    @Column(updatable = false)
    private LocalDateTime createdAt;

    private LocalDateTime updatedAt;

    @PrePersist
    protected void onCreate() {
        createdAt = LocalDateTime.now();
        updatedAt = LocalDateTime.now();
    }

    @PreUpdate
    protected void onUpdate() {
        updatedAt = LocalDateTime.now();
    }
}
