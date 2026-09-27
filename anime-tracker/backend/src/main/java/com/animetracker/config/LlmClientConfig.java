package com.animetracker.config;

import com.animetracker.agent.llm.AnthropicClient;
import com.animetracker.agent.llm.LlmClient;
import com.animetracker.agent.llm.MockLlmClient;
import com.animetracker.agent.llm.OpenAiCompatClient;
import com.animetracker.agent.llm.UnconfiguredLlmClient;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.client.RestTemplate;

/**
 * 按配置选择大模型客户端.
 *
 * 换厂商只需改环境变量, 不用动代码 —— 这是当初决定做协议抽象层的全部理由.
 */
@Configuration
public class LlmClientConfig {

    private static final Logger log = LoggerFactory.getLogger(LlmClientConfig.class);

    @Bean
    public LlmClient llmClient(LlmProperties props, RestTemplate llmRestTemplate, ObjectMapper mapper) {
        String provider = props.getProvider() == null ? "" : props.getProvider().trim().toLowerCase();

        if ("mock".equals(provider)) {
            log.warn("LLM 客户端: 使用 Mock 模式 (不会调用真实模型, 仅供本地联调)");
            return new MockLlmClient();
        }

        if (!props.hasApiKey()) {
            log.warn("LLM 客户端: 未检测到 LLM_API_KEY, AI 助手功能将返回配置提示. "
                    + "其余功能不受影响.");
            return new UnconfiguredLlmClient();
        }

        LlmClient client = switch (provider) {
            case "anthropic", "claude" -> new AnthropicClient(props, llmRestTemplate, mapper);
            case "openai-compat", "openai", "" -> new OpenAiCompatClient(props, llmRestTemplate, mapper);
            default -> {
                log.warn("LLM 客户端: 无法识别的 llm.provider='{}', 已回退为 openai-compat", provider);
                yield new OpenAiCompatClient(props, llmRestTemplate, mapper);
            }
        };

        log.info("LLM 客户端: provider={}, model={}, baseUrl={}",
                client.providerName(), client.modelName(), props.getBaseUrl());
        return client;
    }
}
