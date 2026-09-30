package com.animetracker.entity;

import jakarta.persistence.*;
import lombok.*;
import java.time.LocalDateTime;

/**
 * 追番记录: 一个用户对一部番只有一条.
 *
 * uniqueConstraints 里的约束名与 db/migration 下 V3 建的约束同名, 但两处各管一段:
 * 生产库靠 Flyway 的 DDL 兜底, 测试库是 ddl-auto=create-drop 由 Hibernate 建表,
 * 不写在这里测试库就没有约束, 并发用例会假绿.
 * (Hibernate 的 validate 只比对表与列、不校验约束, 所以两处写重了也不会冲突.)
 */
@Entity
@Table(name = "anime_tracking",
        uniqueConstraints = @UniqueConstraint(
                name = "uk_anime_tracking_user_subject",
                columnNames = {"user_id", "subject_id"}))
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

    /** 两条时间戳取自同一次 {@code now()} —— 分两次调在纳秒级时钟上会落进不同的微秒,
     *  插入的行看着就像"被编辑过"。完整理由见 {@link ReviewReply} 的同名方法。 */
    @PrePersist
    protected void onCreate() {
        LocalDateTime now = LocalDateTime.now();
        createdAt = now;
        updatedAt = now;
    }

    @PreUpdate
    protected void onUpdate() {
        updatedAt = LocalDateTime.now();
    }
}
