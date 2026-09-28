package com.animetracker.config;

import com.animetracker.exception.BusinessException;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 滑动窗口限流器的机制部分 —— 只认「键 + 每分钟配额」, 不含任何业务措辞.
 *
 * <p>从 {@link com.animetracker.agent.AgentRateLimiter} 里抽出来的: 注册/登录也要按 IP
 * 限流, 而窗口本身的做法与「限的是提问还是登录」毫无关系. 与其把那三十行并发代码
 * 抄第二份(两份还会各自演化, 修了一份漏一份), 不如只留一份, 由持有者传入不同的措辞.
 *
 * <p>键用字符串而不是用户 id: 未登录访客也要限流, 他们按 IP 计, 键形如 "user:12" /
 * "ip:203.0.113.7" / "login:ip:203.0.113.7". 不加区分的话, 匿名流量要么没有限制,
 * 要么全站共用一个计数器(一个人刷爆, 所有人被挡).
 *
 * <p>刻意做成普通类而不是 @Component: 限流的语义是「同一份计数器」, 而提问与
 * 注册/登录必须各算各的 —— 共用一个实例时, 访客问几次 AI 就会顺手吃掉自己的登录配额
 * (键前缀不同虽然也能避开, 但那要求每个调用点都记着别写错). 各持有者 new 一个,
 * 计数天然隔离.
 *
 * <p>局限(与抽出来之前一样): 计数在单机内存里, 多实例部署时各算各的. 真要做严格限流
 * 应当换成 Redis 计数.
 */
public class SlidingWindowRateLimiter {

    private static final long WINDOW_MILLIS = 60_000L;

    /** 限流维度 -> 最近一分钟内的命中时间戳 */
    private final Map<String, Deque<Long>> hits = new ConcurrentHashMap<>();

    /**
     * 记一次命中, 超出配额时抛 429.
     *
     * @param key       限流维度, 例如 "user:12" 或 "login:ip:203.0.113.7"
     * @param perMinute 每分钟允许次数
     * @param action    动作名, 只用来拼提示语(「提问」「注册」「登录」)
     */
    public void check(String key, int perMinute, String action) {
        long now = System.currentTimeMillis();
        Deque<Long> window = hits.computeIfAbsent(key == null ? "unknown" : key, k -> new ArrayDeque<>());

        synchronized (window) {
            while (!window.isEmpty() && now - window.peekFirst() > WINDOW_MILLIS) {
                window.pollFirst();
            }
            if (window.size() >= perMinute) {
                throw BusinessException.tooManyRequests(
                        action + "太频繁了, 请稍等一分钟再试 (当前上限 " + perMinute + " 次/分钟)");
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
