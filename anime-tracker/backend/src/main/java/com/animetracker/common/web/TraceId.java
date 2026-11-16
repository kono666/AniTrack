package com.animetracker.common.web;

import org.slf4j.MDC;

import java.util.UUID;
import java.util.regex.Pattern;

/**
 * 一次请求的 traceId：生成、校验、读写 MDC.
 *
 * <p>放在 common/web 而不是某个业务域里，因为它只关心「这次 HTTP 请求」，
 * 与动漫、评论、追番都无关。
 *
 * <p>请求头里的值只接受 {@code [A-Za-z0-9_-]{1,64}}：调用方可以自带 traceId
 * 把网关/前端的链路接上，但不能拿它往日志和响应头里塞换行或超长内容。
 */
public final class TraceId {

    /** 响应头与请求头同名，方便调用方把自己的链路 id 带进来 */
    public static final String HEADER = "X-Trace-Id";

    /** MDC 里的键；日志 pattern 用它把一次请求串起来 */
    public static final String MDC_KEY = "traceId";

    private static final Pattern SAFE = Pattern.compile("[A-Za-z0-9_-]{1,64}");

    private TraceId() {
    }

    /** 当前请求的 traceId；不在请求线程上时返回 null */
    public static String current() {
        return MDC.get(MDC_KEY);
    }

    /** 采信调用方带来的值，不合法就现生成一个 */
    public static String fromHeader(String raw) {
        return raw != null && SAFE.matcher(raw).matches() ? raw : generate();
    }

    /** 16 位十六进制，足够在日志里区分一次请求 */
    public static String generate() {
        return UUID.randomUUID().toString().replace("-", "").substring(0, 16);
    }

    /**
     * 在另一个线程上带着请求线程的 traceId 执行任务.
     *
     * <p>Agent 的对话循环跑在 worker 线程池里，而 MDC 是 ThreadLocal ——
     * 不显式传过去的话，worker 上构造的 ApiResponse 和日志都没有 traceId。
     * 执行完恢复原值，避免污染线程池里下一个任务。
     */
    public static void runWith(String traceId, Runnable task) {
        String previous = MDC.get(MDC_KEY);
        if (traceId != null) {
            MDC.put(MDC_KEY, traceId);
        }
        try {
            task.run();
        } finally {
            if (traceId != null) {
                if (previous != null) {
                    MDC.put(MDC_KEY, previous);
                } else {
                    MDC.remove(MDC_KEY);
                }
            }
        }
    }
}
