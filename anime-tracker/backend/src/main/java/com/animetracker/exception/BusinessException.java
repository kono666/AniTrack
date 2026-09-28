package com.animetracker.exception;

/**
 * 业务异常，用于 Service 层抛出可预期的错误。
 * GlobalExceptionHandler 统一捕获并返回友好信息。
 */
public class BusinessException extends RuntimeException {

    private final int code;

    public BusinessException(String message) {
        super(message);
        this.code = 400;
    }

    public BusinessException(int code, String message) {
        super(message);
        this.code = code;
    }

    public int getCode() {
        return code;
    }

    // ===== 工厂方法 =====

    public static BusinessException notFound(String message) {
        return new BusinessException(404, message);
    }

    public static BusinessException unauthorized(String message) {
        return new BusinessException(401, message);
    }

    public static BusinessException forbidden(String message) {
        return new BusinessException(403, message);
    }

    public static BusinessException badRequest(String message) {
        return new BusinessException(400, message);
    }

    /**
     * 429 Too Many Requests.
     *
     * 登录被锁定用它而不是 403: 403 的意思是「你没有权限, 别试了」,
     * 而这里的意思是「现在不行, 过一会儿再来」—— 两者对前端的意义完全不同.
     * 403 会让前端以为要跳登录页或提示无权限, 429 才对应「等一会儿重试」.
     */
    public static BusinessException tooManyRequests(String message) {
        return new BusinessException(429, message);
    }

    /**
     * 503 Service Unavailable.
     *
     * 用在「服务端自己腾不出手, 与你无关」的场景 —— 目前是 Agent 的 worker 池满了.
     * 和 429 的分工: 429 是「你问得太勤了」, 由**配额**决定, 换个人来照样能问;
     * 503 是「此刻服务器忙不过来」, 与配额无关, 等一会儿大家都好. 两者的重试策略
     * 不一样, 所以码要分开 —— 都并到 429 里, 前端就没法区分「减少自己的频率」
     * 和「换个时间再来」.
     */
    public static BusinessException serviceUnavailable(String message) {
        return new BusinessException(503, message);
    }
}
