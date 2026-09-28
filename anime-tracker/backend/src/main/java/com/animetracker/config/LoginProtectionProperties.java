package com.animetracker.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * 登录爆破防护参数.
 *
 * 抽成配置而不是写死常量, 是因为这两个数字的合理取值完全取决于部署场景:
 * 内网演示系统可以放宽到 20 次, 公网作品集必须收紧. 而登录逻辑本身不该
 * 为了改一个阈值去动代码、重新打包.
 *
 * 默认值的取舍: 5 次 / 15 分钟. 再少会把「手滑打错」的正常用户挡在门外,
 * 再多就等于没防 —— 5 次已经让在线爆破的成本高到不可行
 * (15 分钟内最多试 5 个密码).
 */
@Data
@Component
@ConfigurationProperties(prefix = "app.security.login")
public class LoginProtectionProperties {

    /**
     * 连续失败多少次后锁定账号.
     *
     * 注意是「连续」: 只要中间成功登录一次, 计数就清零.
     */
    private int maxFailures = 5;

    /**
     * 锁定时长 (分钟).
     *
     * 用「限时锁定」而不是「永久锁定」, 是因为这个项目没有找回密码、也没有
     * 邮箱验证 —— 永久锁定意味着任何恶意者只要知道你的用户名, 连试 5 次就能
     * 让这个账号永久无法登录, 那把「防护」变成了「拒绝服务」.
     * 限时锁定把伤害限制在一个可恢复的窗口内.
     */
    private int lockMinutes = 15;
}
