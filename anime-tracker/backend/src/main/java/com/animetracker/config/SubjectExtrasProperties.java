package com.animetracker.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

import java.time.Duration;

/**
 * 附属数据(角色 / 制作人员 / 关联条目)的两个可调参数: 多久算旧, 每分钟允许多少次.
 *
 * <h2>ttl —— 为什么必须有一个"过期"的概念</h2>
 *
 * <p>附属数据不像剧集: 剧集列表基本一次定终身, 而角色会随着新季度增加、制作人员会被
 * 补录、新的关联条目(续集/剧场版)会随着时间出现。如果"取过一次"就永远算数, 那么
 * <b>首播那天取回来的角色表会被永久固化</b> —— 而它看起来完全正常, 没有任何地方会报错,
 * 只是半年后打开还是那几个人。
 *
 * <p>默认 24 小时: 比剧集那一侧激进得多(那边等于永不过期), 因为这一层的三个接口是
 * 单次返回整个集合、一次也就十几到几十 KB, 一天重取一次的成本完全可以忽略; 而"新角色
 * 什么时候能出现"是用户看得见的东西。
 *
 * <p>{@code charactersTtl} / {@code staffTtl} / {@code relationsTtl} 三个字段可以各自
 * 覆盖 {@code ttl}, 默认不设(= 用 ttl)。分成三个而不是一个, 是因为它们的更新节奏不同:
 * 关联条目(续集、剧场版)通常在开播前后才补齐, 而制作人员几乎不会变。今天三个都用同一个
 * 默认值 —— 留出这个口子是因为"哪天只想让其中一块更勤快"是一个显然会来的需求,
 * 而那时再改配置的**形状**要动代码。
 *
 * <p><b>写成 0 或负数是"永远算旧"</b>(每次请求都回源), 不是"永远算新"。这个取值方向
 * 与 {@code anitrack.login-event.retention} 那条正好相反, 所以写明: 那边 0 是"关掉清理",
 * 这边 0 是"一刻都等不了"。
 *
 * <h2>rateLimitPerMinute —— 为什么一个公开 GET 需要限流</h2>
 *
 * <p>{@code /api/bangumi/**} 在 {@code SecurityConfig.publicPaths} 里, 不需要登录。
 * 这一个请求在缓存过期时会**直接驱动一次上游调用**, 而这一层刻意没有用 Caffeine 缓存
 * (见 {@code SubjectExtrasService} 的注释: 四个缓存名被九处钉死), 所以它是站上少见的
 * "陌生人不登录也能让我们的服务端去打第三方"的路径。限流是按调用方 IP 计的。
 */
@Data
@Component
@ConfigurationProperties(prefix = "anitrack.subject-extras")
public class SubjectExtrasProperties {

    /** 一块附属数据最多算"新"多久。早于「现在 − ttl」的会重新回源 */
    private Duration ttl = Duration.ofHours(24);

    /** 角色那一块的覆盖值. 不设(= null)就用 {@link #ttl} */
    private Duration charactersTtl;

    /** 制作人员那一块的覆盖值 */
    private Duration staffTtl;

    /** 关联条目那一块的覆盖值 */
    private Duration relationsTtl;

    /** 每个调用方 IP 每分钟允许多少次. 一次详情页会发三个(三个不同的路径) */
    private int rateLimitPerMinute = 60;

    /**
     * 三块附属数据 —— <b>类型而不是字符串</b>.
     *
     * <p>它同时是"要取哪一块"的入参、在飞集合的键、以及 {@link #ttlFor} 的索引。
     * 用枚举而不是各自传一个字符串, 是因为这三块各自对应一个数据库列、一个上游路径、
     * 一个配置项, 而字符串写错(比如 {@code "staff"} 与 {@code "persons"})不会有任何
     * 编译期信号 —— 只会静默地走到另一个分支或者永远读不到 marker。
     */
    public enum Section {
        CHARACTERS, STAFF, RELATIONS
    }

    /**
     * 这一块用哪个 TTL: 各块的覆盖值优先, 没设就用 {@link #ttl}.
     *
     * <p>写成方法而不是让调用方自己挑字段: "哪一块配了、哪一块没配"这个判断只该有一处,
     * 而三个调用点各写一遍三元表达式就是三处。
     */
    public Duration ttlFor(Section section) {
        Duration override = switch (section) {
            case CHARACTERS -> charactersTtl;
            case STAFF -> staffTtl;
            case RELATIONS -> relationsTtl;
        };
        return override != null ? override : ttl;
    }
}
