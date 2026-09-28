package com.animetracker.agent;

import com.animetracker.config.SlidingWindowRateLimiter;
import org.springframework.stereotype.Component;

/**
 * 单用户提问频率限制.
 *
 * Agent 接口每次调用都会真实花钱, 一旦被恶意刷或前端出现死循环,
 * 账单会非常难看. 这里用滑动窗口做最基础的兜底.
 *
 * 窗口本身已在 {@link SlidingWindowRateLimiter} 里(注册/登录也用同一份机制),
 * 这个类现在只负责两件事: 「提问」这个措辞, 以及自己那份计数器的归属.
 *
 * 局限: 计数在单机内存里, 多实例部署时各算各的. 真要做严格限流应换成 Redis 计数.
 */
@Component
public class AgentRateLimiter {

    /**
     * 自己的一份窗口, 不与注册/登录那条路共用.
     *
     * 共用会让两件事的配额互相消耗: 一个访客问几次 AI, 转头就发现自己登不了录 ——
     * 而这两件事的合理配额本来就差着量级.
     */
    private final SlidingWindowRateLimiter window = new SlidingWindowRateLimiter();

    /**
     * 记录一次提问, 超出配额时抛出业务异常.
     *
     * @param key        限流维度, 例如 "user:12" 或 "ip:203.0.113.7"
     * @param perMinute  每分钟允许次数
     */
    public void check(String key, int perMinute) {
        window.check(key, perMinute, "提问");
    }

    /** 清空全部计数, 供测试使用 */
    public void reset() {
        window.reset();
    }
}
