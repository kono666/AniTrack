package com.animetracker.config;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.options;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 跨域行为端到端测试 (走真实的 Spring Security 过滤器链).
 *
 * 和 SecurityConfigCorsTest 的分工: 那个测「规则是什么」, 这个测「规则真的被装上了」.
 * 只测规则有个隐患 —— 如果 CORS 过滤器根本没注册进链里, 规则写得再对也不生效,
 * 而单元测试仍然全绿.
 *
 * 所以这个类里同时有正向和反向两组断言:
 *   白名单内的来源能拿到 Access-Control-Allow-Origin (证明过滤器确实在工作)
 *   白名单外的来源拿不到 (证明它按白名单在挡)
 * 只有正向那条能过, 反向那条的结论才可信.
 *
 * 关于 properties: 这是本类唯一的「故意配错」之外的差异 ——
 * 线上默认白名单是空的. 这里填一个假域名是为了造出「CORS 已启用」的状态,
 * 否则整个过滤器都不注册, 就没东西可测了.
 */
@SpringBootTest(properties = {
        "spring.datasource.url=jdbc:h2:mem:anitrack-cors;DB_CLOSE_DELAY=-1;MODE=MySQL",
        "spring.jpa.hibernate.ddl-auto=create-drop",
        "app.cors.allowed-origins=http://allowed.example"
})
@AutoConfigureMockMvc
@ActiveProfiles("dev")
class CorsIntegrationTest {

    private static final String ALLOWED_ORIGIN = "http://allowed.example";
    private static final String EVIL_ORIGIN = "http://evil.example";
    /** 公开接口, 不需要登录, 便于把注意力集中在跨域行为上 */
    private static final String PUBLIC_ENDPOINT = "/api/agent/info";

    @Autowired
    private MockMvc mockMvc;

    // ========== 正向: 白名单来源被放行 ==========

    /** 预检请求 (OPTIONS) 要能通过, 且必须回 Access-Control-Allow-Origin */
    @Test
    void preflightFromAllowedOriginIsAccepted() throws Exception {
        mockMvc.perform(options(PUBLIC_ENDPOINT)
                        .header("Origin", ALLOWED_ORIGIN)
                        .header("Access-Control-Request-Method", "GET")
                        .header("Access-Control-Request-Headers", "authorization"))
                .andExpect(status().isOk())
                .andExpect(header().string("Access-Control-Allow-Origin", ALLOWED_ORIGIN));
    }

    @Test
    void actualRequestFromAllowedOriginCarriesCorsHeader() throws Exception {
        mockMvc.perform(get(PUBLIC_ENDPOINT).header("Origin", ALLOWED_ORIGIN))
                .andExpect(status().isOk())
                .andExpect(header().string("Access-Control-Allow-Origin", ALLOWED_ORIGIN));
    }

    // ========== 反向: 白名单外的来源被拒 ==========

    /**
     * 改动前这里会原样回显 Access-Control-Allow-Origin: http://evil.example,
     * 也就是任何网站都能跨域调用本 API. 现在必须被挡掉.
     */
    @Test
    void requestFromUnknownOriginIsRejected() throws Exception {
        mockMvc.perform(get(PUBLIC_ENDPOINT).header("Origin", EVIL_ORIGIN))
                .andExpect(status().isForbidden())
                .andExpect(header().doesNotExist("Access-Control-Allow-Origin"));
    }

    @Test
    void preflightFromUnknownOriginIsRejected() throws Exception {
        mockMvc.perform(options(PUBLIC_ENDPOINT)
                        .header("Origin", EVIL_ORIGIN)
                        .header("Access-Control-Request-Method", "GET"))
                .andExpect(status().isForbidden())
                .andExpect(header().doesNotExist("Access-Control-Allow-Origin"));
    }

    /**
     * 近似来源同样要挡.
     *
     * "http://allowed.example.evil.test" 里含有一段看起来像白名单的字符串,
     * 如果实现是字符串前缀匹配就会被绕过 —— 用全等比对才不会.
     */
    @Test
    void lookalikeOriginIsRejected() throws Exception {
        mockMvc.perform(get(PUBLIC_ENDPOINT).header("Origin", "http://allowed.example.evil.test"))
                .andExpect(status().isForbidden());
    }

    // ========== 凭证: 无论如何都不下发 ==========

    @Test
    void allowedResponseNeverCarriesCredentialsHeader() throws Exception {
        mockMvc.perform(get(PUBLIC_ENDPOINT).header("Origin", ALLOWED_ORIGIN))
                .andExpect(status().isOk())
                .andExpect(header().string("Access-Control-Allow-Origin", ALLOWED_ORIGIN))
                .andExpect(header().doesNotExist("Access-Control-Allow-Credentials"));
    }

    // ========== 同源: 完全不受影响 ==========

    /**
     * 这是本次改动最需要保住的一条: 不带 Origin 的请求就是同源请求,
     * 开发环境的 Vite 代理、生产环境的 nginx 反代走的都是这条路.
     * 收紧 CORS 绝不能让它们出问题.
     */
    @Test
    void sameOriginRequestIsUnaffected() throws Exception {
        mockMvc.perform(get(PUBLIC_ENDPOINT))
                .andExpect(status().isOk())
                .andExpect(header().doesNotExist("Access-Control-Allow-Origin"));
    }

    /** 顺带确认响应体正常, 说明请求是真的走到了 Controller 而不是被过滤器截断 */
    @Test
    void sameOriginRequestStillReturnsPayload() throws Exception {
        mockMvc.perform(get(PUBLIC_ENDPOINT))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200))
                .andExpect(jsonPath("$.data").exists());
    }
}
