package com.animetracker.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.http.converter.StringHttpMessageConverter;
import org.springframework.web.client.RestTemplate;
import org.springframework.web.method.support.HandlerMethodArgumentResolver;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

import java.nio.charset.StandardCharsets;
import java.util.List;

/**
 * 参数解析器与 RestTemplate 的配置.
 *
 * 注意: CORS 刻意不在这里配置.
 * 它统一由 SecurityConfig 的 corsConfigurationSource() 提供. 这里原本也写了一份
 * 一模一样的通配策略 —— 同一个策略有两个出口, 意味着收紧时很容易只改一处、
 * 另一处继续生效. 一个策略只留一个出口.
 */
@Configuration
public class WebConfig implements WebMvcConfigurer {

    private final CurrentUserResolver currentUserResolver;

    public WebConfig(CurrentUserResolver currentUserResolver) {
        this.currentUserResolver = currentUserResolver;
    }

    @Override
    public void addArgumentResolvers(List<HandlerMethodArgumentResolver> resolvers) {
        resolvers.add(currentUserResolver);
    }

    @Bean
    public RestTemplate restTemplate() {
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(10_000);
        factory.setReadTimeout(120_000);
        RestTemplate rt = new RestTemplate(factory);
        // 强制 UTF-8, 解决 Python 响应中文乱码导致数据丢失
        rt.getMessageConverters().add(0,
                new StringHttpMessageConverter(StandardCharsets.UTF_8));
        return rt;
    }
}
