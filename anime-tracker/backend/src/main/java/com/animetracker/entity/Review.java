package com.animetracker.entity;

import jakarta.persistence.*;
import lombok.*;
import java.time.LocalDateTime;

/**
 * 短评: 一个用户对一部番只有一条. 约束说明同 {@link AnimeTracking}.
 */
@Entity
@Table(name = "review",
        uniqueConstraints = @UniqueConstraint(
                name = "uk_review_user_subject",
                columnNames = {"user_id", "subject_id"}))
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class Review {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "user_id", nullable = false)
    private User user;

    /** Bangumi subject_id */
    @Column(name = "subject_id", nullable = false)
    private Integer subjectId;

    /** 评分 1-10 */
    @Column(nullable = false)
    private Integer rating;

    /** 评论内容 */
    @Column(columnDefinition = "TEXT")
    private String content;

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
