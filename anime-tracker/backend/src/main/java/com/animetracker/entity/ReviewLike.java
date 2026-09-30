package com.animetracker.entity;

import jakarta.persistence.*;
import lombok.*;
import java.time.LocalDateTime;

/**
 * 点赞: 一个用户对同一条短评只会有一次. 约束说明同 {@link AnimeTracking}.
 *
 * <p>这条记录同时是两件事的真相来源: 「谁赞了」(按 review_id 查) 与「我赞过没有」
 * (按 review_id + user_id 查)。{@code Review.likeCount} 只是它行数的冗余副本,
 * 两者不一致时**以这张表为准**。
 *
 * <p>删除靠库级的 {@code ON DELETE CASCADE}(V7): 删短评会带走它的点赞行,
 * 不需要(也不该)在 Java 侧手写一遍 —— 删短评的路径有三条, 靠每一条都记得
 * 手写 deleteByReviewId 是迟早会漏的。
 *
 * <p>反过来, {@code user_id} 上的外键**没有**级联, 见 V7 脚本里的说明:
 * 用户删除路径将来出现时, 库级联会静默删掉点赞行而 {@code like_count} 不跟着减。
 */
@Entity
@Table(name = "review_like",
        uniqueConstraints = @UniqueConstraint(
                name = "uk_review_like_review_user",
                columnNames = {"review_id", "user_id"}))
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class ReviewLike {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "review_id", nullable = false)
    private Review review;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "user_id", nullable = false)
    private User user;

    @Column(updatable = false)
    private LocalDateTime createdAt;

    @PrePersist
    protected void onCreate() {
        createdAt = LocalDateTime.now();
    }
}
