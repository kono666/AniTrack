package com.animetracker.agent.llm;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * base-url 归一化.
 *
 * 用户填的 base-url 五花八门: 有的带 /v1, 有的不带, 有的末尾多个斜杠,
 * 甚至有人直接把完整地址粘进来. 这里全部收敛成同一个结果,
 * 否则表现就是「配了 key 却 404」, 而错误信息完全指不到配置上.
 */
class EndpointsTest {

    @ParameterizedTest
    @CsvSource({
            "https://api.deepseek.com,                     https://api.deepseek.com/v1/chat/completions",
            "https://api.deepseek.com/,                    https://api.deepseek.com/v1/chat/completions",
            "https://api.deepseek.com/v1,                  https://api.deepseek.com/v1/chat/completions",
            "https://api.deepseek.com/v1/,                 https://api.deepseek.com/v1/chat/completions",
            "https://api.deepseek.com/v1/chat/completions, https://api.deepseek.com/v1/chat/completions",
            "https://api.deepseek.com/v1//,                https://api.deepseek.com/v1/chat/completions",
    })
    @DisplayName("OpenAI 兼容: 各种写法的 base-url 都收敛到同一个地址")
    void normalizesOpenAiCompatibleBaseUrl(String baseUrl, String expected) {
        assertThat(Endpoints.resolve(baseUrl, "/v1/chat/completions", "/v1", "/chat/completions"))
                .isEqualTo(expected);
    }

    @ParameterizedTest
    @CsvSource({
            "https://api.anthropic.com,       https://api.anthropic.com/v1/messages",
            "https://api.anthropic.com/,      https://api.anthropic.com/v1/messages",
            "https://api.anthropic.com/v1,    https://api.anthropic.com/v1/messages",
            "https://api.anthropic.com/v1/messages, https://api.anthropic.com/v1/messages",
    })
    @DisplayName("Anthropic: 同上")
    void normalizesAnthropicBaseUrl(String baseUrl, String expected) {
        assertThat(Endpoints.resolve(baseUrl, "/v1/messages", "/v1", "/messages"))
                .isEqualTo(expected);
    }

    @Test
    @DisplayName("代理地址带子路径时不能被吃掉")
    void keepsProxySubPath() {
        assertThat(Endpoints.resolve("https://gateway.example.com/llm", "/v1/chat/completions",
                "/v1", "/chat/completions"))
                .isEqualTo("https://gateway.example.com/llm/v1/chat/completions");
    }

    @Test
    @DisplayName("base-url 为空时明确报错, 而不是猜一个厂商域名")
    void throwsWhenBaseUrlMissing() {
        assertThatThrownBy(() -> Endpoints.resolve("", "/v1/chat/completions", "/v1", "/chat/completions"))
                .isInstanceOf(LlmException.class)
                .hasMessageContaining("llm.base-url");
        assertThatThrownBy(() -> Endpoints.resolve(null, "/v1/messages", "/v1", "/messages"))
                .isInstanceOf(LlmException.class);
        assertThatThrownBy(() -> Endpoints.resolve("   ", "/v1/messages", "/v1", "/messages"))
                .isInstanceOf(LlmException.class);
    }
}
