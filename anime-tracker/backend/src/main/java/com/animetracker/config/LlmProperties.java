package com.animetracker.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * LLM 接入配置.
 *
 * 支持两类协议:
 *   anthropic      - Claude 官方 /v1/messages
 *   openai-compat  - DeepSeek / 通义 / 智谱 / Kimi 等 OpenAI 兼容接口
 *
 * 密钥只从环境变量 LLM_API_KEY 读取, 不写进代码库.
 */
@Data
@Component
@ConfigurationProperties(prefix = "llm")
public class LlmProperties {

    /** 协议类型: anthropic | openai-compat */
    private String provider = "openai-compat";

    /** API 基础地址 */
    private String baseUrl = "https://api.deepseek.com";

    /** 密钥, 由环境变量注入 */
    private String apiKey = "";

    /** 模型名 */
    private String model = "deepseek-chat";

    /** 单次回复最大 token */
    private int maxTokens = 2048;

    /** 采样温度 */
    private double temperature = 0.7;

    /** 单次对话最多允许的工具调用轮数, 防死循环烧钱 */
    private int maxToolRounds = 6;

    /** 带入上下文的历史消息条数上限, 防 token 无限增长 */
    private int historyLimit = 20;

    /** 单用户每分钟最多提问次数 */
    private int rateLimitPerMinute = 10;

    /**
     * 全站每日模型调用次数上限, 0 表示不限.
     *
     * 公网 Demo 的关键保险: 密钥在服务器上, 谁都可能来问, 单靠按人限流挡不住
     * 「很多人同时各问几次」. 默认 500 次约合 80~100 次提问, 正常演示绰绰有余,
     * 被人恶意刷也只损失很小的金额.
     */
    private int dailyCallBudget = 500;

    /** 用户输入最大字符数 */
    private int maxInputLength = 1000;

    /**
     * 单个工具返回结果的最大字符数, 超出部分截断.
     * 不加限制的话, 一次列表查询就可能把几万字符灌回模型, 既烧 token 又容易触发上游的长度上限.
     */
    private int maxToolResultChars = 6000;

    /** 连接超时(毫秒) */
    private int connectTimeout = 10_000;

    /** 读取超时(毫秒), 比 Bangumi 的 120s 短, 便于快速失败 */
    private int readTimeout = 90_000;

    /** 密钥是否已配置 */
    public boolean hasApiKey() {
        return apiKey != null && !apiKey.isBlank();
    }
}
