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
 * 直连(对端不可信)时伪造 X-Forwarded-For 必须无效 —— 否则限流等于没有.
 *
 * <p>这是 {@link ForwardedHeaderIntegrationTest} 的另一半. 那条证明「可信任的代理
 * 发来的头会被采信」, 这条证明「不可信任的对端发来的头会被丢掉」. 两条合起来才是
 * 完整的一句话: 能不能信, 取决于**直连的那一方是不是我的代理**, 而不是取决于
 * 这个头本身. 少了这条, 上面那条其实也能被一个「无条件相信 X-Forwarded-For」的
 * 实现满足 —— 而那正是本项目没有选 framework(Spring 的 ForwardedHeaderFilter,
 * 相信任何对端)的原因, 见 application.yml 里 server 那一段的说明.
 *
 * <p>怎么在用例里扮成「不可信的对端」: Tomcat 是按 server.tomcat.remoteip.internal-proxies
 * 判断对端可不可信的, 而用例里的对端永远是回环地址(它默认在可信范围内). 所以这里
 * 把配置改成只信 10.0.0.0/8 —— 回环就不在可信范围里了, 等于站到了「公网直连」的位置上.
 * 这个值**不是**发布配置里的值, 只是这条用例用来摆姿势的.
 *
 * <p>判据: 三个请求分别带 A 的头、带 B 的头、不带, 若头被采信则各是一份干净配额
 * (三次都会是 400); 头被丢掉则三次都记在同一个桶上, 第三次撞上上限 2 -> 429.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
        "spring.datasource.url=jdbc:h2:mem:anitrack-forged-forwarded;DB_CLOSE_DELAY=-1;MODE=MySQL",
        "app.security.rate-limit.login-per-minute=2",
        // 把回环地址踢出可信范围: 场景上等价于「应用直接暴露在公网, 对面就是访客本人」
        "server.tomcat.remoteip.internal-proxies=10.0.0.0/8",
        "anitrack.preload.enabled=false"
})
@ActiveProfiles("dev")
class ForgedForwardedHeaderIntegrationTest {

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
    @DisplayName("对端不可信时伪造的 X-Forwarded-For 被忽略: 换个头骗不到新配额")
    void forgedHeaderIsIgnoredFromAnUntrustedPeer() {
        // 三次请求, 三个不同的「身份」—— 若头被采信, 这三次是三个干净的桶, 都该放行
        assertThat(login("198.51.100.7").getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(login("203.0.113.5").getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);

        // 第三次仍然是 400 才说明头被采信了; 这里是 429, 因为三次都算在同一个真实对端上
        ResponseEntity<String> third = login(null);
        assertThat(third.getStatusCode()).isEqualTo(HttpStatus.TOO_MANY_REQUESTS);
        assertThat(third.getBody()).contains("登录太频繁了");
    }
}
