package com.animetracker.agent;

import com.animetracker.exception.BusinessException;
import org.springframework.stereotype.Component;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 单用户提问频率限制.
 *
 * Agent 接口每次调用都会真实花钱, 一旦被恶意刷或前端出现死循环,
 * 账单会非常难看. 这里用滑动窗口做最基础的兜底.
 *
 * 局限: 计数在单机内存里, 多实例部署时各算各的. 真要做严格限流应换成 Redis 计数.
 */
@Component
public class AgentRateLimiter {

    private static final long WINDOW_MILLIS = 60_000L;

    /**
     * 限流维度 -> 最近一分钟内的提问时间戳.
     *
     * 用字符串而不是用户 id, 是因为未登录访客也要限流 —— 他们按 IP 计,
     * 键形如 "user:12" / "ip:203.0.113.7". 不加区分的话, 匿名流量要么没限制,
     * 要么全站共用一个计数器（一个人刷爆, 所有人被挡）.
     */
    private final Map<String, Deque<Long>> hits = new ConcurrentHashMap<>();

    /**
     * 记录一次提问, 超出配额时抛出业务异常.
     *
     * @param key        限流维度, 例如 "user:12" 或 "ip:203.0.113.7"
     * @param perMinute  每分钟允许次数
     */
    public void check(String key, int perMinute) {
        long now = System.currentTimeMillis();
        Deque<Long> window = hits.computeIfAbsent(key == null ? "unknown" : key, k -> new ArrayDeque<>());

        synchronized (window) {
            while (!window.isEmpty() && now - window.peekFirst() > WINDOW_MILLIS) {
                window.pollFirst();
            }
            if (window.size() >= perMinute) {
                throw new BusinessException(429,
                        "提问太频繁了, 请稍等一分钟再试 (当前上限 " + perMinute + " 次/分钟)");
            }
            window.addLast(now);
        }

        // 顺手清掉已经空了的窗口, 避免长期运行后 Map 无限增长
        if (hits.size() > 1000) {
            hits.entrySet().removeIf(e -> {
                Deque<Long> q = e.getValue();
                synchronized (q) {
                    return q.isEmpty() || now - q.peekLast() > WINDOW_MILLIS;
                }
            });
        }
    }

    /** 清空全部计数, 供测试使用 */
    public void reset() {
        hits.clear();
    }
}
