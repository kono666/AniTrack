package com.animetracker.entity;

import jakarta.persistence.*;
import lombok.*;
import java.time.LocalDateTime;

/**
 * 一条回复: 挂在某条短评下, **扁平一层**, 不能再回复回复.
 *
 * <p>{@code Review} 是 {@code LAZY} 的: 列表接口要读的是「这条回复属于哪条评论」
 * (判删除权限时问它), 而每条回复都把整条评论(连同它的 {@code TEXT} 正文与作者)
 * 读进来是纯浪费。多花一条查询换取不被拖垮的读路径。
 *
 * <p>删除靠库级的 {@code ON DELETE CASCADE}(V8): 删短评会带走它的回复, 删回复会
 * 带走这条回复的赞 —— **两级级联**, 不需要(也不该)在 Java 侧手写。删短评的路径有
 * 三条, 靠每一条都记得手写是迟早会漏的。
 *
 * <p>这条记录的 {@code likeCount} 与 {@code Review.likeCount} 是同一套东西:
 * 一行 {@code reply_like} = 一次赞, 计数列是行数的冗余副本。三个约束也一样
 * ({@code insertable=false} 让列默认值生效、{@code updatable=false} 挡住
 * Hibernate 的默认 UPDATE、类型用基本类型与库里的 {@code NOT NULL} 对齐),
 * 完整理由写在 {@link Review} 那个 {@code likeCount} 字段的注释上, 这里不重复。
 */
@Entity
@Table(name = "review_reply")
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class ReviewReply {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "review_id", nullable = false)
    private Review review;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "user_id", nullable = false)
    private User user;

    /** 回复正文. 与 {@code review.content} 不同, 这里**不许为空** —— 见 V8 脚本的说明. */
    @Column(nullable = false)
    private String content;

    /** 唯一的写入者是 {@code ReviewReplyRepository.increment/decrementLikeCount} */
    @Column(name = "like_count", insertable = false, updatable = false,
            columnDefinition = "BIGINT NOT NULL DEFAULT 0")
    private long likeCount;

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
