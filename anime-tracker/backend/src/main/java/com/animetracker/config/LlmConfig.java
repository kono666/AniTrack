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
 * 不复用 WebConfig 里那个 restTemplate. 两边要的是**相反**的超时口径:
 * 抓 Bangumi 是「拿一份现成的 JSON 回来」, 慢就是不正常, 所以那边读超时只有 20s;
 * 而这里等的是模型逐字生成, 90s 是正常长度 —— 用 20s 会把正常的回答掐断,
 * 前端看到的是「流莫名其妙断了」. 超时值只是最表面的差别: 共用一个客户端意味着
 * 以后任何一边调参都要先确认另一边不受影响, 两个 bean 各自持有自己的 factory 更省事.
 *
 * (这段注释原来写的是「后者读超时 120s」—— 那个数字在收窄 Bangumi 超时时已经不成立了.
 *  注释里写死别人的配置值, 就是给自己埋一个会过期的事实; 所以这里只说口径, 不说数.)
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
