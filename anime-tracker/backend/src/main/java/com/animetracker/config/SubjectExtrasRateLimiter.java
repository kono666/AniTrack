package com.animetracker.config;

import org.springframework.stereotype.Component;

/**
 * 附属数据那三个公开 GET 的限流 —— 按调用方 IP 计.
 *
 * <p><b>为什么一个"只是读"的接口需要限流.</b> 它读的是本地库, 但缓存过期时<b>会直接
 * 驱动一次上游调用</b>: {@code /api/bangumi/**} 在 {@code SecurityConfig.publicPaths} 里
 * 免登录, 所以这是一个陌生人不登录就能让我们的服务端去打第三方的路径。这一层刻意
 * 没有用 Caffeine 缓存(理由见 {@code SubjectExtrasService} 的类注释: 四个缓存名被九处
 * 钉死), 承担"别把上游打爆"这件事的就是这里。
 *
 * <p>形状与 {@code AuthRateLimiter} 一致: {@link SlidingWindowRateLimiter} 是普通类
 * (每个持有者 {@code new} 一个, 计数因此各自独立), 由本类持有并把配额与措辞绑在一处。
 *
 * <p><b>为什么在 controller 里调而不是 service 里。</b> 与 {@code UserController} /
 * {@code AgentController} 两处既有调用一致; 而且限流要看调用方 IP, 那是 HTTP 层的概念,
 * 把它一路传进 service 会让"这个 service 需不需要请求上下文"变成一个每次都要想的问题。
 *
 * <p>⚠️ <b>它是无条件生效的, 不是"只在真要回源时才限"。</b> 代价是同一个出口 IP 后面的
 * 多个用户共享这份配额(一次详情页发三个请求, 60/分钟 ≈ 每分钟 20 次页面浏览) ——
 * 这是刻意选的方向: "只限回源那一路"听起来更精准, 但它把配额与缓存状态绑在一起,
 * 于是同一个人"刷新得快一点"与"看得多一点"会有完全不同的限额, 而这种不一致
 * 解释不清、也测不准。
 */
@Component
public class SubjectExtrasRateLimiter {

    private final SlidingWindowRateLimiter window = new SlidingWindowRateLimiter();
    private final SubjectExtrasProperties props;

    public SubjectExtrasRateLimiter(SubjectExtrasProperties props) {
        this.props = props;
    }

    /** 记一次命中, 超出配额时抛 429(BusinessException.tooManyRequests) */
    public void check(String clientIp) {
        window.check("subject-extras:ip:" + clientIp, props.getRateLimitPerMinute(), "加载附属信息");
    }

    /** 清空计数, 供测试使用 */
    public void reset() {
        window.reset();
    }
}
