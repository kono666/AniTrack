package com.animetracker.config;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.RequestPostProcessor;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 注册/登录限流在真实接口上的表现(走完整过滤器链 + 参数绑定 + 异常处理).
 *
 * <p>为什么有了 {@link AuthRateLimiterTest} 还要这一层: 单元测试直接调限流器,
 * 证明的是「配额算得对」; 而这里要证明的是「它真的挂在这两个接口上」——
 * 少调一次 checkRegister 不会有任何编译期信号, 单元测试会全绿而线上照旧不限流.
 * 同理, 429 的响应体必须真的是项目统一的 {code,message,data} 结构, 前端才读得懂.
 *
 * <p>阈值通过 properties 压到很小(注册 2、登录 3), 才可能在用例里撞到上限;
 * 默认的 5/10 在测试里要发十几个请求, 那既慢又把本来该验证的东西淹没在噪音里.
 *
 * <p>每个用例前 reset(): 同一个 Spring 上下文会被本类的多个用例共用, 而限流计数是
 * 单例里的状态 —— 不清的话第二个用例会拿着第一个用例剩下的配额开始.
 */
@SpringBootTest(properties = {
        "spring.datasource.url=jdbc:h2:mem:anitrack-auth-rate-limit;DB_CLOSE_DELAY=-1;MODE=MySQL",
        "app.security.rate-limit.register-per-minute=2",
        "app.security.rate-limit.login-per-minute=3",
        // 启动预加载会去 api.bgm.tv 拉数据, 与这条用例无关, 关掉让库和日志都安静
        "anitrack.preload.enabled=false"
})
@AutoConfigureMockMvc
@ActiveProfiles("dev")
class AuthRateLimitIntegrationTest {

    private static final String REGISTER_BODY =
            "{\"username\":\"%s\",\"email\":\"%s@example.com\",\"password\":\"passw0rd123\"}";

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private AuthRateLimiter authRateLimiter;

    @BeforeEach
    void resetCounters() {
        authRateLimiter.reset();
    }

    /** MockMvc 默认来源是 127.0.0.1, 所有用例会共用一个桶 —— 所以要能指定来源 IP */
    private static RequestPostProcessor from(String ip) {
        return request -> {
            request.setRemoteAddr(ip);
            return request;
        };
    }

    private static String registerBody(String username) {
        return String.format(REGISTER_BODY, username, username);
    }

    private static String loginBody(String username, String password) {
        return "{\"username\":\"" + username + "\",\"password\":\"" + password + "\"}";
    }

    // ========== 超限 429 ==========

    @Test
    @DisplayName("注册: 同 IP 超过上限后 429, 且响应体是项目统一结构")
    void registerIsRejectedAfterTheQuota() throws Exception {
        for (int i = 0; i < 2; i++) {
            mockMvc.perform(post("/api/user/register").with(from("198.51.100.1"))
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(registerBody("quota" + i)))
                    .andExpect(status().isOk());
        }

        mockMvc.perform(post("/api/user/register").with(from("198.51.100.1"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(registerBody("quota-blocked")))
                .andExpect(status().isTooManyRequests())
                .andExpect(jsonPath("$.code").value(429))
                .andExpect(jsonPath("$.message").value("注册太频繁了, 请稍等一分钟再试 (当前上限 2 次/分钟)"))
                .andExpect(jsonPath("$.data").doesNotExist());
    }

    /**
     * 429 而不是 400: 这条同时证明限流发生在**业务逻辑之前** ——
     * 用户名不存在时 login 抛的是 400「用户名或密码错误」, 只有先撞上限流才会是 429.
     */
    @Test
    @DisplayName("登录: 同 IP 超过上限后 429(而不是业务层的 400)")
    void loginIsRejectedAfterTheQuota() throws Exception {
        for (int i = 0; i < 3; i++) {
            mockMvc.perform(post("/api/user/login").with(from("198.51.100.2"))
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(loginBody("nobody", "passw0rd123")))
                    .andExpect(status().isBadRequest());
        }

        mockMvc.perform(post("/api/user/login").with(from("198.51.100.2"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(loginBody("nobody", "passw0rd123")))
                .andExpect(status().isTooManyRequests())
                .andExpect(jsonPath("$.code").value(429))
                .andExpect(jsonPath("$.message").value("登录太频繁了, 请稍等一分钟再试 (当前上限 3 次/分钟)"));
    }

    // ========== 分桶 ==========

    @Test
    @DisplayName("换一个 IP 就是另一份配额: 别人撞墙不该连累我")
    void anotherIpKeepsItsOwnQuota() throws Exception {
        for (int i = 0; i < 2; i++) {
            mockMvc.perform(post("/api/user/register").with(from("198.51.100.3"))
                    .contentType(MediaType.APPLICATION_JSON).content(registerBody("ip3-" + i)));
        }
        mockMvc.perform(post("/api/user/register").with(from("198.51.100.3"))
                        .contentType(MediaType.APPLICATION_JSON).content(registerBody("ip3-blocked")))
                .andExpect(status().isTooManyRequests());

        mockMvc.perform(post("/api/user/register").with(from("203.0.113.9"))
                        .contentType(MediaType.APPLICATION_JSON).content(registerBody("ip9")))
                .andExpect(status().isOk());
    }

    /**
     * 注册与登录各有一份配额, 撞满一个不该影响另一个.
     *
     * <p>登录要连着做满 3 次(登录上限)才算验到: 只登一次的话, 就算两个动作共用同一个
     * 计数器也照样能过 —— 注册那 2 次还没顶到登录的 3 次上限. 做满 3 次之后共用计数器的
     * 实现会在第 2 次登录就被挡下(那时键上已经有 2 次注册 + 1 次登录), 才会露出来.
     */
    @Test
    @DisplayName("注册与登录是两笔配额: 注册撞满了, 登录的额度仍然是完整的 3 次")
    void registerQuotaDoesNotBlockLogin() throws Exception {
        for (int i = 0; i < 2; i++) {
            mockMvc.perform(post("/api/user/register").with(from("198.51.100.4"))
                    .contentType(MediaType.APPLICATION_JSON).content(registerBody("sep-" + i)))
                    .andExpect(status().isOk());
        }
        mockMvc.perform(post("/api/user/register").with(from("198.51.100.4"))
                        .contentType(MediaType.APPLICATION_JSON).content(registerBody("sep-blocked")))
                .andExpect(status().isTooManyRequests());

        // 同一 IP 的登录额度一次都没被动过: 3 次全通
        for (int i = 0; i < 3; i++) {
            mockMvc.perform(post("/api/user/login").with(from("198.51.100.4"))
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(loginBody("sep-0", "passw0rd123")))
                    .andExpect(status().isOk());
        }
        // 第 4 次才撞到登录自己的上限, 说明计数是从 0 起算的
        mockMvc.perform(post("/api/user/login").with(from("198.51.100.4"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(loginBody("sep-0", "passw0rd123")))
                .andExpect(status().isTooManyRequests());
    }

    // ========== 正常流程不受影响 ==========

    /**
     * 正向对照: 注册完能登录, 两端都拿到 token.
     *
     * <p>这是「限流没有误伤正常用户」的端到端证明 —— 单元测试里限流器是谁也不认识的
     * 一个对象, 而这里走的是真的注册、真的 BCrypt、真的发 JWT.
     */
    @Test
    @DisplayName("正向对照: 注册 -> 登录全程 200, 且都拿得到 token")
    void happyPathStillWorks() throws Exception {
        String username = "happy-path-user";

        mockMvc.perform(post("/api/user/register").with(from("203.0.113.20"))
                        .contentType(MediaType.APPLICATION_JSON).content(registerBody(username)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200))
                .andExpect(jsonPath("$.data.token").isNotEmpty())
                .andExpect(jsonPath("$.data.username").value(username));

        mockMvc.perform(post("/api/user/login").with(from("203.0.113.20"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(loginBody(username, "passw0rd123")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.token").isNotEmpty());
    }

    /**
     * 参数校验没过的请求不该消耗配额 —— 这是「检查放在方法体第一行」的直接后果.
     *
     * <p>这条不是洁癖. 若把限流挪去做成过滤器, 它就会在解析请求体之前计数:
     * 一个写错客户端的程序(或者别人随手扫的畸形请求)发上几十下, 就能把整个 NAT
     * 出口的注册/登录配额打光, 同一间办公室里谁都登不进来. 那样断言会红.
     */
    @Test
    @DisplayName("请求体不合法(400)不吃配额: 发满上限个畸形请求后, 正常注册照样 200")
    void invalidRequestsDoNotConsumeQuota() throws Exception {
        for (int i = 0; i < 5; i++) {
            mockMvc.perform(post("/api/user/register").with(from("198.51.100.5"))
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{\"username\":\"x\"}"))
                    .andExpect(status().isBadRequest());
        }

        mockMvc.perform(post("/api/user/register").with(from("198.51.100.5"))
                        .contentType(MediaType.APPLICATION_JSON).content(registerBody("after-garbage")))
                .andExpect(status().isOk());
    }
}
