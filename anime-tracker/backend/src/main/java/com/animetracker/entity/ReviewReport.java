package com.animetracker.entity;

import jakarta.persistence.*;
import lombok.*;

import java.time.LocalDateTime;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * 举报: 一个用户对同一条短评只会有一次(V13 的 {@code uk_review_report_review_reporter})。
 *
 * <p>与 {@link ReviewLike} 是同一个形状、同一套约束理由(那条唯一约束同时是「谁举报过」
 * 与「我举报过没有」的索引), 差别只有下面两点。
 *
 * <p><b>一、它有状态, 而点赞没有。</b> {@code status} 只有两个值: {@link #PENDING} 与
 * {@link #DISMISSED}。**没有 RESOLVED** —— 「评论已经被删掉了」这件事不靠改状态表达,
 * 靠**查询条件**表达(待处理 = {@code status = 'PENDING'} 且它的评论还在)。这样就没有
 * 「删了又恢复, 举报状态该不该退回去」这种循环状态机: 恢复评论之后那条举报**自动**
 * 回到队列里, 一次回写都不需要。删评论时也不去回写举报行, 那正是要避免的东西。
 *
 * <p><b>二、它的外键里有一条与 {@link ReviewLike} 相反的判断。</b>
 * {@code handled_by} 指向**管理员**(可为 null, 没处理过就是 null)。它是 LAZY 的,
 * 而管理端的明细查询会 {@code JOIN FETCH} 它 —— 与 {@code reporter} 一样,
 * 「谁举报的 / 谁处理的」是这个面板存在的唯一理由。
 *
 * <p>{@code detail} 是选填的补充说明, 上限 500 字(与建表一致)。它**不做过激内容检测**:
 * 这是一条给管理员读的自由文本, 不是要展示给别人的东西。
 */
@Entity
@Table(name = "review_report",
        uniqueConstraints = @UniqueConstraint(
                name = "uk_review_report_review_reporter",
                columnNames = {"review_id", "reporter_id"}))
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class ReviewReport {

    // ==================== 理由白名单 ====================

    public static final String SPAM = "SPAM";
    public static final String ABUSE = "ABUSE";
    public static final String SPOILER = "SPOILER";
    public static final String OTHER = "OTHER";

    /**
     * 合法的举报理由. 写接口上不在这个集合里的值回 400。
     *
     * <p>用 {@link LinkedHashSet} 而不是 {@code Set.of}：与 {@code AdminActionLog.ACTIONS}
     * 逐字相同的理由 —— {@code Set.of} 建的不可变集合 =={@code contains(null)} 抛
     * {@code NullPointerException}==, 而这里是**校验入口**, 少一个 null 判断就是一条
     * 400 变成 500 的路。顺序也无所谓, 但固定顺序让「前端照着这个顺序渲染单选框」
     * 不至于每次构建都换一个样。
     *
     * <p><b>为什么是四个固定的档位而不是让用户自由写一句话</b>: 自由文本没法筛、没法
     * 统计, 也没法在列表上用一个标签显示; 而「这条评论哪里有问题」这件事的答案本来就
     * 高度集中在这四类里。需要补充说明的走 {@code detail}。
     */
    public static final Set<String> REASONS = Collections.unmodifiableSet(
            new LinkedHashSet<>(List.of(SPAM, ABUSE, SPOILER, OTHER)));

    // ==================== 状态 ====================

    /** 未处理. 建表时是这一列的 DEFAULT */
    public static final String PENDING = "PENDING";

    /** 管理员看过之后认为不需要动作. **只能从 PENDING 走到这里, 没有回头路** */
    public static final String DISMISSED = "DISMISSED";

    // ==================== 字段 ====================

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /**
     * 被举报的评论. 删评论时这一行**跟着走**(库级 {@code ON DELETE CASCADE})——
     * 与账本 {@code admin_action_log} 的判断**正好相反**, 因为两者是不同的东西:
     * 账本是史料(必须活得比被记录的对象久), 举报是待办(对象没了, 这条就没意义了)。
     */
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "review_id", nullable = false)
    private Review review;

    /** 举报人. 外键**不级联**, 理由见 V13 脚本 */
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "reporter_id", nullable = false)
    private User reporter;

    /** 取值见本类的常量, 合法集合是 {@link #REASONS} */
    @Column(nullable = false, length = 32)
    private String reason;

    /** 选填补充说明, 上限 500 字 */
    @Column(length = 500)
    private String detail;

    /** 取值见本类的常量, 合法集合只有 {@link #PENDING} 与 {@link #DISMISSED} */
    @Column(nullable = false, length = 16)
    private String status;

    /** 处理人. 未处理时为 null —— 与 {@code handledAt} 同生共死 */
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "handled_by")
    private User handler;

    private LocalDateTime handledAt;

    @Column(updatable = false)
    private LocalDateTime createdAt;

    /**
     * 补默认值. <b>时间戳之外, 这里还要给 {@code status} 兜底</b> —— 建表那一列有
     * {@code DEFAULT 'PENDING'}, 但那只是数据库的默认值: Hibernate 插入时如果这个字段
     * 是 null, 它会**显式写一个 NULL 进去**, 而那一列是 NOT NULL, 于是插入直接失败。
     * 也就是说「有 DEFAULT 就够了」在这里是错的。
     *
     * <p>用 {@code @PrePersist} 而不是在 builder 调用处写死: 那是第二处定义, 迟早漏一处。
     */
    @PrePersist
    protected void onCreate() {
        createdAt = LocalDateTime.now();
        if (status == null) {
            status = PENDING;
        }
    }
}
