package com.animetracker.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * 注册 / 登录的按 IP 限流阈值.
 *
 * <p>与 {@link LoginProtectionProperties} 一样抽成配置, 理由也一样: 合理取值完全取决于
 * 部署场景. 自己家里跑的演示可以放宽, 公网作品集必须收紧, 而限流逻辑不该为了改一个
 * 数字去动代码重新打包. 两个字段都能用环境变量覆盖.
 *
 * <p>默认值 5 与 10: 注册比登录更该收紧 —— 正常用户一辈子注册一次, 而一个 IP 后面
 * 可能坐着整个办公室.
 *
 * <p>要注意这两个数字是**按 IP** 的, 对 NAT(公司/学校/运营商大内网)后面的用户是共享的:
 * 一条出口 IP 上的十个人同时登录就会撞到 10 次/分钟. 所以登录给得比注册宽,
 * 而且出问题时调环境变量即可, 不必改代码.
 */
@Data
@Component
@ConfigurationProperties(prefix = "app.security.rate-limit")
public class AuthRateLimitProperties {

    /** 每个 IP 每分钟允许注册几次 */
    private int registerPerMinute = 5;

    /** 每个 IP 每分钟允许登录几次 */
    private int loginPerMinute = 10;
}
