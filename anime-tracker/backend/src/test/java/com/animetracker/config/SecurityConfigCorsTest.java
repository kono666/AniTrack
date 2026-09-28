package com.animetracker.config;

import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.CorsConfigurationSource;

import java.util.Arrays;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

/**
 * 跨域规则本体测试.
 *
 * 直接构造 SecurityConfig 并取它产出的 CorsConfigurationSource, 看规则本身
 * 放行/拦截哪些来源 —— 不启动 Spring 上下文, 所以每条规则都能单独钉住.
 *
 * 这里覆盖的正是改动前的三个问题: 来源通配、放行凭证、方法通配.
 */
class SecurityConfigCorsTest {

    private static CorsConfigurationSource sourceOf(String... origins) {
        CorsProperties props = new CorsProperties();
        props.setAllowedOrigins(Arrays.asList(origins));
        SecurityConfig config = new SecurityConfig(mock(JwtAuthFilter.class), props);
        return config.corsConfigurationSource();
    }

    private static CorsConfiguration configOf(String... origins) {
        CorsConfigurationSource source = sourceOf(origins);
        // 路径用真实接口, 确保 URL 匹配规则确实命中
        CorsConfiguration cfg = source.getCorsConfiguration(
                new MockHttpServletRequest("GET", "/api/agent/info"));
        assertThat(cfg).as("CorsConfiguration 不该为 null").isNotNull();
        return cfg;
    }

    // ========== 默认: 谁都不放行 ==========

    /**
     * 空白名单时, 任何来源都不放行.
     *
     * 注意这一条在真实运行时的表现和「配置了白名单但不含该来源」不同:
     * 空白名单时 CORS 过滤器根本不注册 (见 filterChain), 请求会走到后面的
     * 鉴权逻辑; 而这里是直接问规则本身, 所以两者都必须是拒绝.
     */
    @Test
    void emptyWhitelistAllowsNoOrigin() {
        CorsConfiguration cfg = configOf();

        assertThat(cfg.checkOrigin("http://evil.example")).isNull();
        assertThat(cfg.checkOrigin("http://localhost:5173")).isNull();
        assertThat(cfg.checkOrigin(null)).isNull();
    }

    // ========== 白名单: 只放行名单内的 ==========

    @Test
    void whitelistAllowsOnlyListedOrigin() {
        CorsConfiguration cfg = configOf("http://localhost:5173", "https://anitrack.example.com");

        assertThat(cfg.checkOrigin("http://localhost:5173")).isEqualTo("http://localhost:5173");
        assertThat(cfg.checkOrigin("https://anitrack.example.com")).isEqualTo("https://anitrack.example.com");
    }

    /** 换个端口、换个协议、加个子域名都不算同一个来源, 必须一律拒绝 */
    @Test
    void whitelistRejectsLookalikeOrigins() {
        CorsConfiguration cfg = configOf("https://anitrack.example.com");

        assertThat(cfg.checkOrigin("http://anitrack.example.com")).isNull();
        assertThat(cfg.checkOrigin("https://anitrack.example.com:8443")).isNull();
        assertThat(cfg.checkOrigin("https://evil-anitrack.example.com")).isNull();
        assertThat(cfg.checkOrigin("https://anitrack.example.com.evil.test")).isNull();
        assertThat(cfg.checkOrigin("http://evil.example")).isNull();
    }

    // ========== 凭证: 任何情况下都不放行 ==========

    /**
     * 无论白名单怎么配, 都不能回 Access-Control-Allow-Credentials: true.
     *
     * 「通配来源 + 允许携带凭证」是 CORS 规范里被明确禁止的组合, 因为它等于
     * 允许任何网站拿着用户浏览器里已有的 Cookie 去调用本 API.
     * 本项目用 Authorization 头传 Bearer token, 由前端代码显式添加,
     * 不依赖浏览器自动携带, 所以关掉它毫无代价.
     */
    @Test
    void credentialsAreNeverAllowed() {
        assertThat(configOf().getAllowCredentials()).isFalse();
        assertThat(configOf("http://localhost:5173").getAllowCredentials()).isFalse();
        assertThat(configOf("*").getAllowCredentials()).isFalse();
    }

    // ========== 通配来源: 只有在显式配置时才生效 ==========

    @Test
    void wildcardAllowsAnyOriginButStillWithoutCredentials() {
        CorsConfiguration cfg = configOf("*");

        assertThat(cfg.checkOrigin("http://anything.test")).isEqualTo("http://anything.test");
        // 关键: 即使来源通配, 也不放行凭证
        assertThat(cfg.getAllowCredentials()).isFalse();
    }

    // ========== 方法: 显式列表, 不用通配 ==========

    @Test
    void methodsAreExplicitAndIncludePreflight() {
        List<String> methods = configOf("http://localhost:5173").getAllowedMethods();

        assertThat(methods).isNotNull();
        assertThat(methods).doesNotContain("*");
        assertThat(methods).contains("GET", "POST", "PUT", "PATCH", "DELETE");
        // 预检请求本身是 OPTIONS, 不放行它等于所有跨域请求都失败
        assertThat(methods).contains("OPTIONS");
    }

    /** 收窄的副作用要防住: TRACE/HEAD 这类没在用的方法不能被顺带放进来 */
    @Test
    void methodsRejectUnusedVerbs() {
        CorsConfiguration cfg = configOf("http://localhost:5173");

        // 注意 checkHttpMethod 返回的是「这个请求被放行时, 允许的方法列表」,
        // null 表示该方法不被允许.
        assertThat(cfg.checkHttpMethod(HttpMethod.TRACE)).isNull();
        assertThat(cfg.checkHttpMethod(HttpMethod.HEAD)).isNull();
        assertThat(cfg.checkHttpMethod(HttpMethod.GET)).contains(HttpMethod.GET, HttpMethod.OPTIONS);
    }

    // ========== 请求头 ==========

    /** Authorization 必须放行, 否则跨域场景下前端带不上 token */
    @Test
    void allowedHeadersCoverAuthAndJson() {
        List<String> headers = configOf("http://localhost:5173").getAllowedHeaders();

        assertThat(headers).isNotNull();
        assertThat(headers).doesNotContain("*");
        assertThat(headers).contains("Authorization", "Content-Type");

        CorsConfiguration cfg = configOf("http://localhost:5173");
        assertThat(cfg.checkHeaders(List.of("authorization", "content-type"))).isNotNull();
    }

    // ========== 预检结果缓存 ==========

    /** maxAge 为 null 时每次跨域请求都要多一次 OPTIONS 往返, 显式配了才对 */
    @Test
    void preflightResultIsCached() {
        assertThat(configOf("http://localhost:5173").getMaxAge()).isNotNull().isPositive();
    }
}
