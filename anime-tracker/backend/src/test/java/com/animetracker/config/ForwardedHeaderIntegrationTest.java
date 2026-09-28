package com.animetracker.config;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.test.context.ActiveProfiles;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 反向代理后面, 按 IP 的限流要按**真实访客**分桶 —— 也就是
 * {@code server.forward-headers-strategy: native} 这一行配置的行为验证.
 *
 * <p>为什么必须开真端口(RANDOM_PORT)而不是 MockMvc: 要验的是 **Tomcat 的
 * RemoteIpValve**. MockMvc 根本不经过 Tomcat —— 它直接调 DispatcherServlet,
 * 请求对象是测试自己造的, 在那儿 setRemoteAddr("198.51.100.7") 想设成什么就是什么,
 * 完全绕开了「谁来改写这个值、什么条件下才肯改写」这个真正的问题. 换句话说
 * 用 MockMvc 写这条用例, 就算配置那行删掉它照样是绿的, 那就不如不写.
 * 这里让请求真的走一遍 HTTP 连接, 由真的 Tomcat 处理, 才咬得住那行配置.
 *
 * <p>用例里拿到的东西: 转账 IP 不同 -> 打满一个不影响另一个; 而且**不带**这个头的
 * 请求走的是直连对端(回环地址)自己的桶. 最后这条是关键判据 —— 开关没打开时,
 * 带头的请求会被算在回环这一份配额上, 它是第三个到场的, 就会是 429 而不是 400.
 *
 * <p>「对端不可信时这个头会被忽略」是同一件事的另一半, 在
 * {@link ForgedForwardedHeaderIntegrationTest} 里 —— 那边要改可信网段, 起不了同一个
 * Spring 上下文, 所以是两个类.
 *
 * <p>阈值压到 2: 默认的 10 次意味着用例里要发十几个请求, 每个请求都真跑一遍 BCrypt
 * (约 100ms), 又慢又把要验的东西淹没在噪音里.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
        "spring.datasource.url=jdbc:h2:mem:anitrack-forwarded-headers;DB_CLOSE_DELAY=-1;MODE=MySQL",
        "app.security.rate-limit.login-per-minute=2",
        // 启动预加载会去 api.bgm.tv 拉数据, 与这条用例无关
        "anitrack.preload.enabled=false"
})
@ActiveProfiles("dev")
class ForwardedHeaderIntegrationTest {

    /** 用文档保留网段(198.51.100.0/24 与 203.0.113.0/24), 免得与真实地址混淆 */
    private static final String VISITOR_A = "198.51.100.7";
    private static final String VISITOR_B = "203.0.113.5";

    /** 用户名不存在, 所以走不到写库; 密码只要能过 @Valid 即可 */
    private static final String LOGIN_BODY =
            "{\"username\":\"nobody-here\",\"password\":\"passw0rd123\"}";

    @Autowired
    private TestRestTemplate rest;

    @Autowired
    private AuthRateLimiter authRateLimiter;

    @BeforeEach
    void resetCounters() {
        authRateLimiter.reset();
    }

    /** 发一次登录请求, 带上(或不带)代理写的 X-Forwarded-For */
    private ResponseEntity<String> login(String forwardedFor) {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        if (forwardedFor != null) {
            headers.set("X-Forwarded-For", forwardedFor);
        }
        return rest.postForEntity("/api/user/login",
                new HttpEntity<>(LOGIN_BODY, headers), String.class);
    }

    @Test
    @DisplayName("转发头里的 IP 就是限流的分桶依据: 打满一个访客不牵连另一个, 也不牵连直连")
    void forwardedIpDecidesTheBucket() {
        // 用户名不存在 -> 业务层 400「用户名或密码错误」. 用 400 当「这次放行了」的
        // 信号: 它证明请求真的走到了业务逻辑, 而不是被限流挡在门外.
        assertThat(login(VISITOR_A).getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(login(VISITOR_A).getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);

        ResponseEntity<String> blocked = login(VISITOR_A);
        assertThat(blocked.getStatusCode()).isEqualTo(HttpStatus.TOO_MANY_REQUESTS);
        // 429 的响应体是项目统一结构 —— 证明是**我们自己的限流器**拦下的,
        // 而不是容器或别的什么东西碰巧回了个 429
        assertThat(blocked.getBody()).contains("登录太频繁了").contains("\"code\":429");

        // 另一个访客: 自己的一份配额, 一次都没被动过
        assertThat(login(VISITOR_B).getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);

        // 不带这个头的请求落在直连对端(回环地址)自己的桶里, 同样没被动过.
        // 开关没打开时这里是 429 —— 那时上面几次请求全都记在回环这一份配额上.
        assertThat(login(null).getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
    }
}
