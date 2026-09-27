package com.animetracker.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.http.converter.StringHttpMessageConverter;
import org.springframework.web.client.RestTemplate;

import java.nio.charset.StandardCharsets;

/**
 * LLM 专用的 HTTP 客户端.
 *
 * 不复用 WebConfig 里那个 restTemplate: 后者读超时 120s 是为慢速抓取 Bangumi 设的,
 * LLM 需要更短的超时, 以便上游无响应时快速失败并给用户友好提示, 而不是干等两分钟.
 */
@Configuration
public class LlmConfig {

    @Bean
    public RestTemplate llmRestTemplate(LlmProperties props) {
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(props.getConnectTimeout());
        factory.setReadTimeout(props.getReadTimeout());
        RestTemplate rt = new RestTemplate(factory);
        // 与项目内其它 HTTP 客户端保持一致, 强制 UTF-8 避免中文乱码
        rt.getMessageConverters().add(0,
                new StringHttpMessageConverter(StandardCharsets.UTF_8));
        return rt;
    }
}
