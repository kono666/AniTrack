package com.animetracker.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * 首次启动时的账号引导配置.
 *
 * 这些值决定「数据库还是空的时候，用什么凭据创建第一个管理员」。
 * 单独的配置类而不是散落的 @Value, 是因为它们是一组语义相关的值,
 * 而且「生产环境不提供任何默认密码」这件事需要在一个地方说清楚。
 *
 * 与 JwtUtil 的差别值得一提: JwtUtil 必须知道当前 profile, 因为
 * 「兜底密钥」这个值在 dev 合法、在 prod 违法, 同一个值在不同环境下
 * 合法性不同. 这里不需要知道 profile —— 「没有密码就不能创建管理员」
 * 是跨环境都成立的规则, 至于 dev 有默认密码、prod 没有, 那是配置的
 * 差异, 交给 application.yml 按 profile 表达. 规则归代码, 取值归配置.
 */
@Data
@Component
@ConfigurationProperties(prefix = "app.bootstrap")
public class BootstrapProperties {

    /** 初始管理员用户名 */
    private String adminUsername = "admin";

    /**
     * 初始管理员密码.
     *
     * 开发环境由 application.yml 的 dev 段给出兜底值, 生产环境默认为空 ——
     * 空值会让 DataInitializer 在「数据库里还没有管理员」时拒绝启动.
     */
    private String adminPassword = "";

    /** 初始管理员邮箱, 留空则不写入 */
    private String adminEmail = "";

    /**
     * 是否创建开发用演示账号 (test/test123).
     *
     * 这个账号的密码是公开写在 README 里的, 所以它只能在本地存在.
     * 用配置开关而不是在当前类里判断 profile, 是为了让「生产环境
     * 不该有这个账号」这件事在配置文件里就能一眼看到.
     */
    private boolean demoUserEnabled = false;
}
