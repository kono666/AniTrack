package com.animetracker.entity;

import jakarta.persistence.*;
import lombok.*;
import java.time.LocalDateTime;

@Entity
@Table(name = "`user`")
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class User {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, unique = true, length = 50)
    private String username;

    @Column(nullable = false)
    private String password;

    /**
     * 邮箱.
     *
     * 唯一, 但**刻意不加 nullable = false**.
     *
     * 「注册必须填邮箱」这条规则由 RegisterRequest 的 @NotBlank 在接口入口保证,
     * 而数据库这一层必须允许 NULL —— 表里已经有历史数据是空邮箱的, 给已有数据的
     * 列加 NOT NULL 约束会让 H2 和 PostgreSQL 双双拒绝执行, 结果是「改了代码之后
     * 服务起不来」. 本项目还没有数据库迁移工具来先回填再收紧约束
     * (见 README「已知限制」), 所以这里选择应用层强约束 + 数据库层只加唯一索引.
     *
     * SQL 标准里 UNIQUE 不约束 NULL: 多个 NULL 可以共存, 所以这个唯一索引
     * 对历史空值行是安全的.
     *
     * ⚠ 一个实测出来的坑: ddl-auto=update 只在**新建表**时创建这个唯一约束,
     * 对已存在的表它只补列、不补约束 (实测: 迁移后的库里只有 PK 和 username 的
     * 唯一索引, email 上没有).
     *
     * 现在补这一刀由迁移脚本负责: 全新的库在建表脚本 (db/migration/[h2|postgres]/V1__init_schema.sql)
     * 里就有这条约束, 老库由 V2__align_legacy_schema.sql 补上, 启动时自动执行,
     * 不再需要谁去手工敲 ALTER TABLE.
     * 在这之前, 邮箱唯一性由 UserService.register() 的 existsByEmail 保证 ——
     * 它能挡住重复注册, 但并发请求之间仍有微小窗口, 数据库约束才是最终防线.
     */
    @Column(unique = true, length = 100)
    private String email;

    @Column(length = 255)
    private String avatar;

    /** 角色: USER / ADMIN */
    @Column(nullable = false, length = 10)
    @Builder.Default
    private String role = "USER";

    /** 账号状态: ACTIVE / DISABLED */
    @Column(nullable = false, length = 10)
    @Builder.Default
    private String status = "ACTIVE";

    /**
     * 连续登录失败次数.
     *
     * 用可空 Integer 而不是 int, 理由和 email 一样: 给已有表加一列 NOT NULL
     * 会因为没有 DEFAULT 子句而迁移失败. 读取一律走 failedAttemptsOrZero().
     */
    private Integer failedAttempts;

    /** 锁定截止时间; 为空表示当前未锁定. 锁定期满即自动失效, 不需要定时任务去清扫 */
    private LocalDateTime lockedUntil;

    /**
     * 最后一次修改密码的时刻; 为空表示从未改过密码.
     *
     * <p><b>它唯一的用途是让「改密之前签发的 token」失效。</b> 本仓的 JWT 是无状态的、
     * 不带 jti, 改完密码旧 token 本来能一直用到 7 天过期 —— 而改密码最常见的两个触发点
     * (用户怀疑账号被盗、管理员强制重置) 的全部意义就是把别人手里那把钥匙作废。
     * 判断落在 {@code JwtAuthFilter} 上, 每次请求多看一眼这一列, 不额外发查询
     * (那个用户本来就是现查的)。
     *
     * <p><b>为什么可空。</b> 与 {@code failedAttempts} 同一条理由: 给已有表加 NOT NULL 列
     * 会因为没有 DEFAULT 而迁移失败。语义上也正好 —— NULL 读作「从未改过密码」,
     * 于是存量用户一个都不会被踢下线。
     *
     * <p>比较时注意<b>精度</b>: 这一列是 {@code TIMESTAMP(6)} 微秒, 而 JWT 的 {@code iat}
     * 只有**秒**。直接比会把「同一秒内改密、立刻用新 token」误拒, 所以那一侧先把这一列
     * 向下取整到秒再比, 见 {@code JwtAuthFilter.isStaleAfterPasswordChange}。
     */
    private LocalDateTime passwordChangedAt;

    @Column(updatable = false)
    private LocalDateTime createdAt;

    @PrePersist
    protected void onCreate() {
        createdAt = LocalDateTime.now();
    }

    /** 统一读失败次数, 免得每个使用点都写一遍判空 */
    public int failedAttemptsOrZero() {
        return failedAttempts == null ? 0 : failedAttempts;
    }

    /**
     * 现在是否处于锁定期.
     *
     * 判断放在实体上而不是 Service 里, 是因为「锁定」是账号自身的状态,
     * 登录校验、管理端列表、解锁接口都要问同一个问题. 集中一处才不会出现
     * 某处用了 isBefore、某处用了 isAfter 这种不一致.
     */
    public boolean isLocked() {
        return lockedUntil != null && lockedUntil.isAfter(LocalDateTime.now());
    }
}
