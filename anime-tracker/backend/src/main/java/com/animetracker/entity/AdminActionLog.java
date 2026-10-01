package com.animetracker.entity;

import jakarta.persistence.*;
import lombok.*;
import java.time.LocalDateTime;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * 管理端一次破坏性操作的留痕。
 *
 * <p><b>它记的是「已经发生过的事实」，所以它不依赖目标还在不在。</b> 这张表上一个外键
 * 都没有（全 schema 唯一一张这样的表），完整理由写在
 * {@code db/migration/h2/V9__add_admin_action_log.sql} 的头部注释里；一句话版本：
 * 挂 CASCADE 会在目标被删时把处理记录一起抹掉，不挂 CASCADE 就是 RESTRICT、会让一次
 * 正常删除撞上莫名其妙的约束错误。
 *
 * <p><b>为什么 {@code actorId} 是普通字段而不是 {@code @ManyToOne User}。</b> 两个原因，
 * 缺一个都不该这么写：表上没有外键；而且 {@code @ManyToOne} 会让 Hibernate 在写这一行时
 * 去挂一个托管实体 —— 那正是 {@link com.animetracker.service.IsolatedInsert#attempt}
 * 明确不接受的形状（它的事务一结束实体就脱离持久化上下文了）。
 *
 * <p>{@code actorName} 是**当时**的用户名快照，不是 join 出来的：actor 改名或账号被删
 * 之后，账本仍然要读得懂。只有 id 的话，一次改名就把历史记录变成了几个认不出来的数字。
 *
 * <p>{@code detail} 存的是**人类可读的一句话**（「把用户 bob 的角色从 USER 改为 ADMIN」），
 * 不是给程序解析的 JSON。审计要回答的是「谁在什么时候对谁做了什么」。
 */
@Entity
@Table(name = "admin_action_log")
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class AdminActionLog {

    /** 封禁一个用户 */
    public static final String USER_BAN = "USER_BAN";
    /** 启用一个被禁用的用户 */
    public static final String USER_UNBAN = "USER_UNBAN";
    /** 改角色 —— 四个动作里最该被查的一个：它能把任何人提成管理员 */
    public static final String USER_ROLE = "USER_ROLE";
    /** 解除登录失败锁定 */
    public static final String USER_UNLOCK = "USER_UNLOCK";
    /**
     * 管理员重置某个用户的密码.
     *
     * <p>它是四个动作之外唯一一个**不改变权限、却能把人挡在门外**的动作: 重置之后
     * 那个人手上的所有 token 立刻作废, 而他能不能再进来, 取决于有没有人把新密码告诉他。
     * 滥用它的后果与「封禁」很接近, 所以它必须和其它四个一样留下一条账。
     */
    public static final String USER_PASSWORD_RESET = "USER_PASSWORD_RESET";
    /** 管理员删除别人的评论 —— V14 起"删除"是软删(置 review.deleted_at), 于是它可以被撤销 */
    public static final String REVIEW_DELETE = "REVIEW_DELETE";
    /**
     * 撤销上面那次删除, 把评论放回架上(V14).
     *
     * <p>它必须单独占一个取值, 而不是"删掉那条 REVIEW_DELETE": 账本记的是**发生过的事实**,
     * 撤销是第二件事实。一条评论的来龙去脉读起来就该是这两行 —— 先删后恢复, 各自有时间、
     * 各有操作人(可能不是同一个管理员)。
     *
     * <p>它也不算"破坏性操作"了(撤销怎么会是破坏); 与其它几条放在同一个取值集合里,
     * 是因为这个集合的用途是**筛选项白名单**, 不是"危险动作清单" —— 危险动作那一个是前端的
     * {@code Audit.vue} 里的 DANGEROUS 数组。
     */
    public static final String REVIEW_RESTORE = "REVIEW_RESTORE";

    public static final String TARGET_USER = "USER";
    public static final String TARGET_REVIEW = "REVIEW";

    /**
     * 全部合法的 {@code action} 取值。
     *
     * <p>读路径拿它判断「这个筛选值是认识的还是垃圾」—— 不认识的当**不筛**处理，而不是
     * 400（与 {@code AdminService.getUserPage} 对未知 role/status 的口径一致）。
     *
     * <p>用 {@link LinkedHashSet} 而不是 {@code Set.of}：与 {@code AdminService.ROLES}
     * 同一条理由 —— {@code Set.of} 的迭代顺序每次启动都不一样，任何把它拼进提示语、
     * 或者被测试断言顺序的地方都会随启动漂移。
     */
    public static final Set<String> ACTIONS = Collections.unmodifiableSet(
            new LinkedHashSet<>(List.of(USER_BAN, USER_UNBAN, USER_ROLE, USER_UNLOCK,
                    USER_PASSWORD_RESET, REVIEW_DELETE, REVIEW_RESTORE)));

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** 操作者 id。没有外键，见类注释 */
    @Column(name = "actor_id", nullable = false)
    private Long actorId;

    /** 操作者当时的用户名快照 */
    @Column(name = "actor_name", nullable = false)
    private String actorName;

    /** 取值见本类的常量，合法集合是 {@link #ACTIONS} */
    @Column(nullable = false)
    private String action;

    /** 取值 {@link #TARGET_USER} / {@link #TARGET_REVIEW} */
    @Column(name = "target_type", nullable = false)
    private String targetType;

    @Column(name = "target_id", nullable = false)
    private Long targetId;

    /** 人类可读的一句话。可以为空（将来若有动作没有值得记的细节） */
    private String detail;

    @Column(name = "created_at", updatable = false)
    private LocalDateTime createdAt;

    @PrePersist
    protected void onCreate() {
        createdAt = LocalDateTime.now();
    }
}
