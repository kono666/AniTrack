package com.animetracker.entity;

import jakarta.persistence.*;
import lombok.*;
import java.time.LocalDateTime;

/**
 * 一条站内通知: 「有人对我做了一件事」。
 *
 * <p>三种类型共用一张表(见 {@code V11__add_notifications.sql} 的头部注释): 收件人、
 * 读没读、排序、分页对三者是同一套逻辑, 切成三张表的话未读计数要发三条 COUNT 再相加、
 * 列表要三路归并、已读要三条 UPDATE。
 *
 * <p><b>与 {@link AdminActionLog} 的对照是这个类最值得读的一段:</b> 两张表的形状几乎
 * 一样, 判断却完全相反 ——
 *
 * <ul>
 *   <li>账本记的是**史料**, 一个外键都不挂, 目标没了记录还得在;</li>
 *   <li>通知是**待办清单**, 指向的东西没了这条通知就没有意义, 所以 review / reply
 *       挂 {@code ON DELETE CASCADE} 让库自己带走它。</li>
 * </ul>
 *
 * 下一个人很可能想把其中一处「统一」成另一处的样子 —— 那是错的, 理由写在 V9 与 V11
 * 两份迁移的头部。
 *
 * <p><b>未读就是 {@code readAt == null}</b>, 没有布尔列: 一个字段同时回答"读没读"和
 * "什么时候读的"。
 *
 * <p>{@code review} 在三种类型上都写(理由见 V11 头部): 读路径要从它拿 {@code subjectId}
 * 和评论摘要, 三条类型走同一条取数路径。
 */
@Entity
@Table(name = "notification")
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class Notification {

    /** 有人回复了我写的短评 */
    public static final String REPLY = "REPLY";
    /** 有人赞了我写的短评 */
    public static final String REVIEW_LIKE = "REVIEW_LIKE";
    /** 有人赞了我写的回复 */
    public static final String REPLY_LIKE = "REPLY_LIKE";

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** 收件人: 被回复/被赞的那条内容的作者。**不是**动作的发起者 */
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "recipient_id", nullable = false)
    private User recipient;

    /** 动作的发起者: 回复的人 / 点赞的人。列表里显示"谁干的" */
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "actor_id", nullable = false)
    private User actor;

    /** 取值见本类的常量 */
    @Column(nullable = false)
    private String type;

    /** 这次动作落在哪条短评上。三种类型都不为空 */
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "review_id")
    private Review review;

    /** 落在哪条回复上。只有 {@link #REPLY} 与 {@link #REPLY_LIKE} 有 */
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "reply_id")
    private ReviewReply reply;

    /** 读过的时间。为 null 就是未读 —— 见类注释 */
    @Column(name = "read_at")
    private LocalDateTime readAt;

    @Column(name = "created_at", updatable = false)
    private LocalDateTime createdAt;

    @PrePersist
    protected void onCreate() {
        createdAt = LocalDateTime.now();
    }
}
