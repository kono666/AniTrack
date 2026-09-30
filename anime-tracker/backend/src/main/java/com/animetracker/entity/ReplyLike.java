package com.animetracker.entity;

import jakarta.persistence.*;
import lombok.*;
import java.time.LocalDateTime;

/**
 * 回复的赞: 一个用户对同一条回复只会有一次. 约束说明同 {@link AnimeTracking}.
 *
 * <p>与 {@link ReviewLike} 是**两条独立的记录**, 不是同一条上的两个标记: 一条评论的赞
 * 与它下面某条回复的赞互不影响 —— 赞了评论不等于赞了它的回复, 反之亦然。两张表而不是
 * 一张带 {@code target_type} 的表, 是因为两者的父行不同、级联路径也不同(删评论 →
 * 删回复 → 删回复的赞, 两级), 而多态外键没法交给数据库去级联。
 *
 * <p>删除靠库级的 {@code ON DELETE CASCADE}(V8): 删回复会带走它的赞。计数列
 * ({@code review_reply.like_count}) 是这张表行数的冗余副本, 理由同
 * {@link Review#likeCount} 的注释。
 */
@Entity
@Table(name = "reply_like",
        uniqueConstraints = @UniqueConstraint(
                name = "uk_reply_like_reply_user",
                columnNames = {"reply_id", "user_id"}))
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class ReplyLike {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "reply_id", nullable = false)
    private ReviewReply reply;

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
