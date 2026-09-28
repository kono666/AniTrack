package com.animetracker.config;

import org.springframework.stereotype.Component;

/**
 * 注册 / 登录接口的按 IP 限流.
 *
 * <p><b>为什么这两个接口需要它</b>
 *
 * <p>它们是全项目仅有的两个「不登录也能打」的写接口, 而且每一下都真的烧 CPU:
 * 登录对每个请求跑一次 BCrypt(约 100ms, 这是算法故意设计的慢), 用户名不存在时
 * 还要陪着跑一次(见 UserService.burnPasswordCheck, 那是为了不泄露「这个用户名存不存在」).
 * 于是几十行的脚本就能把 CPU 占满 —— 这与「有人刷 AI 接口烧钱」是同一类问题,
 * 只是代价从账单换成了 CPU.
 *
 * <p>另一个原因是横向遍历: 登录原本只有「按账号锁定」, 那是纵向防护 —— 盯着一个账号
 * 猛试会被锁上. 换成拿一份用户名字典挨个试一次, 每个账号都只错一次, 谁也锁不上.
 * 按 IP 限流才拦得住这种扫法. 注册同理: 不限的话可以批量注册.
 *
 * <p><b>为什么检查放在控制器方法体里, 而不是做成过滤器</b>
 *
 * <p>配额保护的是 BCrypt 那 ~100ms 的 CPU, 而只有**通过参数校验**的请求才会走到
 * BCrypt —— 请求体缺字段、不是合法 JSON 的, 在方法体之前就被 400/415 挡掉了,
 * 一毫秒 CPU 都不花. 计数放在方法体第一行, 正好让配额只被「真要花钱」的请求消耗.
 *
 * <p>反过来, 过滤器会在解析请求体之前计数: 一个写错客户端的程序发一百个畸形请求,
 * 就能把整个办公室(NAT 后同一个 IP)的登录配额一起打光 —— 那是把防护变成了拒绝服务.
 */
@Component
public class AuthRateLimiter {

    /** 与 AI 提问各持一份窗口, 互不消耗对方的配额, 理由见 SlidingWindowRateLimiter */
    private final SlidingWindowRateLimiter window = new SlidingWindowRateLimiter();

    private final AuthRateLimitProperties props;

    public AuthRateLimiter(AuthRateLimitProperties props) {
        this.props = props;
    }

    /**
     * 注册前检查.
     *
     * 键里带上动作名: 注册与登录是两笔独立的配额, 撞了注册不该把人挡在登录外面.
     */
    public void checkRegister(String clientIp) {
        window.check("register:ip:" + clientIp, props.getRegisterPerMinute(), "注册");
    }

    /** 登录前检查. 与注册分开计数, 理由同上. */
    public void checkLogin(String clientIp) {
        window.check("login:ip:" + clientIp, props.getLoginPerMinute(), "登录");
    }

    /** 清空全部计数, 供测试使用 */
    public void reset() {
        window.reset();
    }
}
