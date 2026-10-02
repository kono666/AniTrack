package com.animetracker.entity;

import jakarta.persistence.*;
import lombok.*;
import java.time.LocalDateTime;

/**
 * 一次登录尝试 —— 成功或失败各算一次。
 *
 * <p><b>这是全项目第一张「为统计而建」的表</b>，所以它为什么必须存在、为什么是事件而不是
 * 状态，完整理由写在 {@code db/migration/h2/V16__add_login_event.sql} 的头部注释里；
 * 一句话版本：{@code user.last_login_at} 只能给出「最后一次」，画不出曲线也数不出日活，
 * 而活跃度天然是<b>事件</b>。
 *
 * <p><b>它是旁路，不是登录流程的一部分。</b> 写入唯一入口是
 * {@link com.animetracker.service.LoginEventService#record}，那里把「插不进去只打日志、
 * 绝不影响登录结果」写成结构上的保证。这张表写丢一行，损失的是一个统计样本；
 * 而让一次成功的登录因为记不上账而失败，损失的是用户。
 *
 * <p><b>{@code userId} 是普通字段而不是 {@code @ManyToOne User}</b>，理由与
 * {@link AdminActionLog} 逐字相同：表上没有外键（可空，见迁移脚本），而 {@code @ManyToOne}
 * 会在写这一行时去挂一个托管实体，那正是 {@code IsolatedInsert} 明确不接受的形状。
 *
 * <p><b>{@code ip}</b> 存的是 {@code getRemoteAddr()} 的结果（Tomcat 已按
 * {@code X-Forwarded-For} 重写过，见 {@code UserController.login}），长度 45 是 IPv6
 * 文本形式的上限。可以为空 —— 取不到就写 null，不塞占位值。
 */
@Entity
@Table(name = "login_event")
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class LoginEvent {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** 登录尝试归属的用户。**可空**：用户名根本不存在的那一类失败没有用户可挂 */
    @Column(name = "user_id")
    private Long userId;

    /** 事件时刻。服务端本地时间，与 {@code last_login_at} 同一口径 */
    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;

    /** 来源地址，见类注释 */
    @Column(length = 45)
    private String ip;

    /** 这次尝试成功了没有。失败也记 —— 那才是爆破的痕迹 */
    @Column(nullable = false)
    private boolean success;

    /**
     * 没显式给时刻就取现在。
     *
     * <p><b>为什么是「没给才取」而不是无条件覆盖</b>（{@link AdminActionLog} 那一边就是
     * 无条件覆盖）：这一列是这张表存在的全部意义，而「补记一条三天前的事件」是它绕不开的
     * 用法 —— 留出显式赋值的口子，测试才可能构造出「14 天前有人来过」这样的历史，
     * 从而让曲线与保留期清理这两件事有办法被真的验一遍。无条件覆盖的话，那两条测试
     * 只能对着一个永远只有今天的表写，验不到任何东西。
     */
    @PrePersist
    protected void onCreate() {
        if (createdAt == null) {
            createdAt = LocalDateTime.now();
        }
    }
}
