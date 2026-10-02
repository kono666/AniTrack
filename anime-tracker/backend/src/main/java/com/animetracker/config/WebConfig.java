package com.animetracker.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.client.ClientHttpResponse;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.http.converter.StringHttpMessageConverter;
import org.springframework.web.client.ResponseErrorHandler;
import org.springframework.web.client.RestTemplate;
import org.springframework.web.method.support.HandlerMethodArgumentResolver;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

import java.io.IOException;
import java.net.HttpURLConnection;
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

    /**
     * 取封面图用的 HTTP 客户端, 只给 {@code CoverImageService} 用.
     *
     * <p><b>为什么是第三个客户端, 而不是复用上面那个.</b> 上面那个是抓 Bangumi
     * <b>接口</b>的: 超时按 JSON 的量级给(20s), 而且是"跟着 302 走"的默认行为。
     * 这一条恰恰相反 —— 它取的是**用户给的一个 URL**, 所以有三件事必须与它不同:
     *
     * <ol>
     *   <li><b>不跟随重定向</b>(覆写 {@code prepareConnection} 关掉
     *       {@code HttpURLConnection.setInstanceFollowRedirects}). 这是这条路上最大的
     *       一个洞: 白名单只校验了**第一跳**的地址, 而 {@code lain.bgm.tv} 只要回一个
     *       302 到 {@code http://169.254.169.254/…}(云主机元数据端点), 客户端就会
     *       老老实实跟过去 —— 于是"只信任一个域名"在白名单校验通过之后立刻失效。
     *       关掉之后 3xx 会原样回到我们手里, 由 {@code CoverImageService} 一律当失败。
     *
     *       <p>为什么是覆写 {@code prepareConnection} 而不是调一个 setter:
     *       {@link SimpleClientHttpRequestFactory} <b>没有</b> {@code setInstanceFollowRedirects}
     *       (那一个是 Apache HttpClient 那个工厂的 API), 而它自己的
     *       {@code prepareConnection} 里恰恰有一行
     *       {@code connection.setInstanceFollowRedirects("GET".equals(httpMethod))} ——
     *       也就是说 GET 默认**是**跟随的。这个覆写正是覆盖那一行, 必须在
     *       {@code super} 之后调。</li>
     *   <li><b>超时更短</b>(连 5s / 读 10s). 取一张图片不该等 20 秒; 而且这个请求
     *       发生在页面渲染的路径上, 一屏可能有几十张 —— 每张都占着一个 Tomcat 线程。</li>
     *   <li><b>错误处理器不读响应体</b>. {@code DefaultResponseErrorHandler} 在
     *       4xx/5xx 时会把上游的响应体<b>整段读进内存</b>去拼异常消息, 而它读之前
     *       不看 {@code Content-Length} —— 这正是这条接口特意要堵的那类无界读取,
     *       只不过换了个地方发生。置空之后状态码由我们自己在读流时判断, 一律有界。</li>
     * </ol>
     *
     * <p>刻意<b>不加</b> {@code StringHttpMessageConverter}: 这条路上走的是
     * {@code RestTemplate.execute(...)} 加自己读流, <b>根本不经过消息转换器</b>。
     * 加一个在那儿只会让人以为它在起作用 —— 与 {@code CacheProperties} 注释里
     * 那些"读不到的死配置"是同一种毛病。
     */
    @Bean
    public RestTemplate imageRestTemplate() {
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory() {
            @Override
            protected void prepareConnection(HttpURLConnection connection, String httpMethod)
                    throws IOException {
                super.prepareConnection(connection, httpMethod);
                // 必须在 super 之后: 它自己那一行是 setInstanceFollowRedirects("GET".equals(...))
                connection.setInstanceFollowRedirects(false);
            }
        };
        factory.setConnectTimeout(5_000);
        factory.setReadTimeout(10_000);

        RestTemplate rt = new RestTemplate(factory);
        rt.setErrorHandler(new ResponseErrorHandler() {
            /**
             * 一律说"没有错误", 好让 {@code doExecute} 不提前抛、直接把响应交给
             * 我们那条读流的代码 —— 上限因此对 4xx/5xx 也一样生效.
             *
             * <p>只实现这一个方法: {@code ResponseErrorHandler} 上唯一必须实现的就是它,
             * {@code handleError} 是 default 且只在 {@code hasError} 为真时才会被调到 ——
             * 这里恒假, 所以<b>刻意不覆写它</b>. 覆写一个永远不会执行的方法, 与
             * {@code CacheProperties} 注释里那批"读不到的死配置"是同一种毛病:
             * 看起来是一道防线, 实际一次都不会生效.
             */
            @Override
            public boolean hasError(ClientHttpResponse response) {
                return false;
            }
        });
        return rt;
    }
}
