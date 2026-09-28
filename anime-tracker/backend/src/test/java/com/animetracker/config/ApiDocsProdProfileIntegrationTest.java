package com.animetracker.config;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 生产 profile 下接口文档必须不存在 —— 而且是「不存在」, 不是「没权限」.
 *
 * <p>要咬住的东西很具体: 这个 profile 里 springdoc 是两个开关一起关掉的. 少关一个
 * (比如只关 swagger-ui) 不会有任何编译期信号, 本地跑 dev 也一切正常 —— 只有生产上
 * 一个注册用户 GET /v3/api-docs 就能拿到整站接口清单时才看得出来. 所以这里用**已登录
 * 的普通用户**去请求: 那种情况下 404 只有一个解释, 就是端点压根没注册.
 *
 * <p>为什么用 MockMvc 而不是真容器: 这里验的是路由与过滤器链的结果(端点在不在、匿名
 * 能不能过), 不涉及异步/连接器行为, MockMvc 跑的就是完整的过滤链, 足够且更快.
 *
 * <p>为什么要覆盖一堆数据源属性: 跑 postgres profile 得有一台真 PostgreSQL, 而本机与
 * CI 都没有 —— 于是把数据源换成内存 H2、把迁移脚本指回 H2 那套, 其余照 postgres
 * profile 原样. 本用例要验的是这个 profile 里的 springdoc 开关, 与底层是哪个数据库
 * 无关; 反过来, 真去连 PG 会让「有没有 PG」变成这条用例的前置条件, 那才是最坏的结果
 * —— 一个安全配置的断言因为环境缺失而长期被跳过.
 *
 * <p>JWT 密钥与管理员密码必须在这里补上: postgres profile 刻意不给这两个兜底值
 * (宁可起不来, 也不要拿公开的默认值上线), 不补上下文就起不来 —— 这正是 4.5 要的
 * fail-fast, 在测试里如实体现为「必须显式提供」.
 */
@SpringBootTest(properties = {
        "spring.datasource.url=jdbc:h2:mem:anitrack-apidocs-prod;DB_CLOSE_DELAY=-1;MODE=MySQL",
        "spring.datasource.driver-class-name=org.h2.Driver",
        "spring.jpa.database-platform=org.hibernate.dialect.H2Dialect",
        "spring.flyway.locations=classpath:db/migration/h2",
        // 仅测试用; 生产由 JWT_SECRET / ADMIN_PASSWORD 环境变量提供
        "jwt.secret=test-only-secret-for-api-docs-check-0123456789abcdef",
        "app.bootstrap.admin-password=TestOnly123456",
        "app.bootstrap.admin-email=admin@example.com",
        "anitrack.preload.enabled=false"
})
@AutoConfigureMockMvc
@ActiveProfiles("postgres")
class ApiDocsProdProfileIntegrationTest {

    private static final String API_DOCS = "/v3/api-docs";
    private static final String SWAGGER_UI = "/swagger-ui/index.html";

    @Autowired
    private MockMvc mockMvc;

    /**
     * 注册一个普通用户并登录, 返回它的令牌.
     *
     * <p>注册接口是公开的, 所以「拿到一个普通用户令牌」对任何人都只是一次请求的事 ——
     * 这正是这条权限边界最该被钉住的地方.
     *
     * <p>用户名由调用方给: 同一个 Spring 上下文(也就是同一个内存库)会被本类的多个
     * 用例共用, 固定用户名的话第二个用例会撞上「用户名已存在」的 400.
     */
    private String registerAndLogin(String username) throws Exception {
        String password = "passw0rd123";
        mockMvc.perform(post("/api/user/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"username\":\"" + username + "\",\"email\":\"" + username
                                + "@example.com\",\"password\":\"" + password + "\"}"))
                .andExpect(status().isOk());

        String body = mockMvc.perform(post("/api/user/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"username\":\"" + username + "\",\"password\":\"" + password + "\"}"))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();

        // 直接抠出 token: 这里只需要一个能过认证的请求头, 不必引入 JSON 解析
        int start = body.indexOf("\"token\":\"") + "\"token\":\"".length();
        return body.substring(start, body.indexOf('"', start));
    }

    @Test
    @DisplayName("已登录的普通用户请求接口文档 -> 404(端点不存在), 而不是 200")
    void docsAreNotRegisteredForAnyUser() throws Exception {
        String token = registerAndLogin("docsprobe1");

        mockMvc.perform(get(API_DOCS).header(HttpHeaders.AUTHORIZATION, "Bearer " + token))
                .andExpect(status().isNotFound());
        mockMvc.perform(get(SWAGGER_UI).header(HttpHeaders.AUTHORIZATION, "Bearer " + token))
                .andExpect(status().isNotFound());
    }

    /**
     * 同一次运行里的对照: 换一条确定存在的接口, 同一个令牌必须能拿到 200.
     *
     * <p>少了这一条, 上面那两个 404 就有别的解释 —— 比如令牌其实没生效(401 早就被
     * 前面的断言挡住, 但若哪天有人把 /api/** 整体放行, 404 就可能来自「路径写错」),
     * 或者上下文根本是空的. 有了它, 404 才只能归因于「端点没注册」.
     */
    @Test
    @DisplayName("同样的令牌请求一个真实接口 -> 200, 证明 404 不是别的原因")
    void theSameTokenWorksOnARealEndpoint() throws Exception {
        String token = registerAndLogin("docsprobe2");

        mockMvc.perform(get("/api/agent/info").header(HttpHeaders.AUTHORIZATION, "Bearer " + token))
                .andExpect(status().isOk());
    }

    /**
     * 匿名请求这个路径拿到的仍然是 401, 不是 404.
     *
     * <p>值得单独钉住: 生产的 permitAll 名单里没有接口文档这三条路径, 所以过滤器链在
     * 路由之前就把匿名请求拦下了. 也就是说「关掉 springdoc」与「匿名进不来」是两道
     * 相互独立的防线 —— 上面那条 404 是从已登录用户的角度证明端点没了, 这条是从匿名
     * 用户的角度证明门也没开.
     */
    @Test
    @DisplayName("匿名请求接口文档 -> 401(过滤器链先拦下, 与端点是否注册无关)")
    void anonymousIsStillRejectedByTheFilterChain() throws Exception {
        mockMvc.perform(get(API_DOCS)).andExpect(status().isUnauthorized());
    }
}
