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

    /**
     * 抓 Bangumi 用的 HTTP 客户端(全项目只有 {@link com.animetracker.service.BangumiApiClient} 用它).
     *
     * <p><b>读超时从 120s 收到 20s</b>. 原来那个 120s 不是「留了余量」,
     * 是把上游的故障时长直接搬成了自己的故障时长:
     * <ul>
     *   <li>日历接口在请求路径上(cache miss 时现拉). 上游黑洞掉一个包, 就是一个
     *       用户请求的线程被占住两分钟 —— 页面转圈, 而日志里什么都没有;</li>
     *   <li>启动预加载和每小时刷新都在这条路上. 预加载 8 个关键词串行, 每个都等满
     *       120s 的话是 16 分钟, 而且它每次失败都只是静默跳过(
     *       见 CachePreloader 里那几个 catch) —— 「一直在超时」和「一切正常」
     *       在日志上几乎一样.</li>
     * </ul>
     *
     * <p>20s 的依据: Bangumi 正常响应在 1-3 秒量级(日历这个接口最大, 也就几秒),
     * 20s 是十倍量级的余量. 收窄之后超时是**有反馈**的: 抛出的异常会被上层那几个
     * catch 转成 warn 日志(见 DataRefreshService), 于是「这一轮没刷到」是能查的,
     * 而不是等两分钟然后什么都不说.
     *
     * <p>它和 LLM 那个 90s 是两个东西, 别一起改: 那边等的是模型逐字生成, 慢是正常的,
     * 断言「20 秒没吐完就算失败」会砍掉正常的回答; 这边等的是把一份现成的 JSON 拿回来,
     * 慢就是不正常. 两个客户端各自持有自己的 factory, 理由见 LlmConfig.
     */
    @Bean
    public RestTemplate restTemplate() {
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(10_000);
        factory.setReadTimeout(20_000);
        RestTemplate rt = new RestTemplate(factory);
        // 强制 UTF-8, 解决 Python 响应中文乱码导致数据丢失
        rt.getMessageConverters().add(0,
                new StringHttpMessageConverter(StandardCharsets.UTF_8));
        return rt;
    }
}
