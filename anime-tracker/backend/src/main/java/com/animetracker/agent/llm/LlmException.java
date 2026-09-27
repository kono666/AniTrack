package com.animetracker.agent.llm;

/**
 * LLM 调用失败.
 *
 * message 是面向用户可读的中文说明, 刻意不携带上游原始响应体 ——
 * 部分厂商在报错时会回显请求内容, 直接透传有泄漏密钥的风险.
 */
public class LlmException extends RuntimeException {

    public LlmException(String message) {
        super(message);
    }

    public LlmException(String message, Throwable cause) {
        super(message, cause);
    }
}
