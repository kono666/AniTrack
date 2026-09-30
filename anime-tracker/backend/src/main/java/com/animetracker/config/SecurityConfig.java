package com.animetracker.config;

import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.Environment;
import org.springframework.http.HttpMethod;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.CorsConfigurationSource;
import org.springframework.web.cors.UrlBasedCorsConfigurationSource;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

@Configuration
@EnableWebSecurity
public class SecurityConfig {

    private static final Logger log = LoggerFactory.getLogger(SecurityConfig.class);

    private final JwtAuthFilter jwtAuthFilter;
    private final CorsProperties corsProperties;

    public SecurityConfig(JwtAuthFilter jwtAuthFilter, CorsProperties corsProperties) {
        this.jwtAuthFilter = jwtAuthFilter;
        this.corsProperties = corsProperties;
    }

    @Bean
    public SecurityFilterChain filterChain(HttpSecurity http, Environment env) throws Exception {
        // 调试入口 (接口文档 / H2 控制台) 只在开发环境放行.
        // 生产环境不暴露, 避免接口结构被外部探测.
        boolean devProfile = Arrays.asList(env.getActiveProfiles()).contains("dev");

        List<String> publicPaths = new ArrayList<>(List.of(
                "/api/user/register",
                "/api/user/login",
                "/api/bangumi/**",
                "/api/review/list",
                "/api/review/stats",
                "/api/stats/anime-heat",
                // 探活端点必须免登录: 请求它的是 Docker HEALTHCHECK、CI 冒烟脚本、
                // nginx 的反代探针, 它们手里不可能有 JWT.
                // 目前它只回 {"status":"UP"|"DOWN"}, 不含任何内部结构.
                //
                // 写两条而不是一条: 字符串匹配是「路径完全相等」, 不带通配符时
                // /actuator/health 匹配不到 /actuator/health/liveness 这样的子路径,
                // 而容器探针和负载均衡用的恰恰是分组后的子路径.
                "/actuator/health",
                "/actuator/health/**"
        ));
        if (devProfile) {
            publicPaths.addAll(List.of(
                    "/h2-console/**",
                    "/swagger-ui/**",
                    "/swagger-ui.html",
                    "/v3/api-docs/**"
            ));
        }

        // CORS 只在白名单非空时启用.
        //
        // 空白名单不是「启用一个什么都不放行的配置」, 而是完全不注册 CORS ——
        // 本项目是同源部署 (开发靠 Vite 代理, 生产靠 nginx 反代 /api), 根本不需要它.
        // 少注册一层过滤器, 就少一处可能误伤同源请求的边界情况.
        if (corsProperties.isEnabled()) {
            http.cors(cors -> cors.configurationSource(corsConfigurationSource()));
            log.info("CORS 已启用, 允许的前端来源: {}", corsProperties.originList());
            if (corsProperties.allowsAnyOrigin()) {
                log.warn("app.cors.allowed-origins 含通配符 *, 任何网站都能跨域调用本 API; "
                        + "若非有意为之, 请改成具体的前端域名");
            }
        } else {
            http.cors(AbstractHttpConfigurer::disable);
            log.info("CORS 未启用 (app.cors.allowed-origins 为空) —— 按同源部署处理");
        }

        http
                .csrf(csrf -> csrf.disable())
                .sessionManagement(sm -> sm.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .authorizeHttpRequests(auth -> auth
                        // 公开接口
                        .requestMatchers(publicPaths.toArray(new String[0])).permitAll()
                        // 评论的三个附属视图免登录: 「谁赞了这条评论」「这条评论下的回复」
                        // 「谁赞了这条回复」。评论列表本身是公开的, 这些都是它的附属信息;
                        // 漏掉的表现是"未登录访客看得见评论, 一点就 401" —— 那不是权限设计,
                        // 是漏配。
                        //
                        // 这几条必须限定 GET, 不能写成上面 publicPaths 那种不带方法的路径串:
                        // requestMatchers(String...) 匹配的是**路径, 不看方法**,
                        // 而 /api/review/{reviewId}/replies 上同时挂着 POST(发回复)——
                        // 一条不带方法的通配会把"写"也一起放行: 匿名 POST 于是穿过过滤器链
                        // 进到 service, 在 user.getId() 上 NPE 成 500, 而不是 401。
                        // 这是实测踩到过的(哨兵是 ReviewReplyIntegrationTest 里那条
                        // 「匿名发回复 401」), 不是设想。所以宁可多写一个 HttpMethod.GET,
                        // 也不要把"今天这个路径上恰好没有写方法"当成一条不变量。
                        //
                        // 通配符在这一层是生效的(与上面 /actuator/health/** 同理) ——
                        // 没有通配符时字符串匹配是"路径完全相等", 这正是 health 写成两条的原因。
                        .requestMatchers(HttpMethod.GET,
                                "/api/review/*/likes",
                                "/api/review/*/replies",
                                "/api/reply/*/likes").permitAll()
                        // AI 对话对访客开放: 未登录时工具注册表只会放出公开工具, 不存在越权路径.
                        // 开放是因为「不用注册就能试」对作品展示很重要, 由限流负责成本兜底.
                        .requestMatchers(HttpMethod.POST,
                                "/api/agent/chat", "/api/agent/chat/stream").permitAll()
                        .requestMatchers(HttpMethod.GET, "/api/agent/info").permitAll()
                        // 管理员接口
                        .requestMatchers("/api/admin/**").hasRole("ADMIN")
                        // Actuator 的兜底规则.
                        //
                        // 目前只暴露了 health 一个端点 (application.yml 的 exposure 清单),
                        // 所以这条现在几乎碰不到. 它防的是「以后有人把清单放宽」:
                        // env / beans / configprops 会原样打印配置与环境变量,
                        // 一旦被 exposure 放出来, 若没有这条规则, 任何一个登录用户
                        // (而不仅是管理员) 都能读到.
                        // 换句话说, 放开暴露清单的操作只会影响管理员, 不会变成对外的洞.
                        .requestMatchers("/actuator/**").hasRole("ADMIN")
                        // 其他接口需要登录
                        .anyRequest().authenticated())
                .addFilterBefore(jwtAuthFilter, UsernamePasswordAuthenticationFilter.class)
                // 认证与授权的失败要分开表达:
                //   401 = 没带凭证或凭证失效  -> 前端清登录态、跳登录页
                //   403 = 已登录但权限不够    -> 前端只提示, 不该把人踢出去
                // 两者都回项目统一的 JSON 结构, 否则前端拿到的是 Spring 默认的错误体, 读不出 message.
                .exceptionHandling(ex -> ex
                        .authenticationEntryPoint((request, response, authException) ->
                                writeJson(response, HttpServletResponse.SC_UNAUTHORIZED, "请先登录"))
                        // 必须显式配置: 只设 authenticationEntryPoint 时, 已登录但越权的请求
                        // 也会被送进入口点, 结果是普通用户访问管理接口收到 401 而不是 403
                        .accessDeniedHandler((request, response, deniedException) ->
                                writeJson(response, HttpServletResponse.SC_FORBIDDEN, "没有权限执行该操作")))
                // H2 控制台以 iframe 呈现, 放开同源 frame 限制 (不放行跨域嵌套)
                .headers(headers -> headers.frameOptions(fo -> fo.sameOrigin()));

        return http.build();
    }

    /** 按项目统一的 ApiResponse 结构写回错误, 编码固定 UTF-8 以免中文提示变乱码 */
    private static void writeJson(HttpServletResponse response, int status, String message) throws IOException {
        response.setStatus(status);
        response.setContentType("application/json");
        response.setCharacterEncoding("UTF-8");
        response.getWriter().write("{\"code\":" + status + ",\"message\":\"" + message + "\",\"data\":null}");
    }

    @Bean
    public PasswordEncoder passwordEncoder() {
        return new BCryptPasswordEncoder();
    }

    /**
     * 跨域规则.
     *
     * 只在 app.cors.allowed-origins 非空时才会被用到 (见 filterChain), 其余时候形同虚设.
     *
     * 三点刻意的收紧, 对比改动前后:
     *   1) 来源: 从「任意来源」改成「白名单里的来源」. 只有配了 * 才退回通配.
     *   2) 凭证: 从 allowCredentials(true) 改成不放行.
     *      通配来源 + 允许携带凭证是 CORS 里明确禁止的组合, 因为那等于允许
     *      任何网站拿着用户的 Cookie 去调用本 API. 本项目用 Bearer token 认证,
     *      token 放在 Authorization 头里由前端自己带上, 不依赖浏览器自动发送,
     *      所以关掉它没有任何副作用.
     *   3) 方法: 从 * 收窄成实际用到的 5 个. 通配会把 TRACE 之类也放进来.
     */
    @Bean
    public CorsConfigurationSource corsConfigurationSource() {
        CorsConfiguration config = new CorsConfiguration();

        if (corsProperties.allowsAnyOrigin()) {
            // 显式写了 * 才走通配. 用 originPatterns 而非 origins, 因为
            // setAllowedOrigins(List.of("*")) 在放行凭证时会被 Spring 直接拒绝.
            config.setAllowedOriginPatterns(List.of("*"));
        } else {
            config.setAllowedOrigins(corsProperties.originList());
        }

        config.setAllowedMethods(List.of("GET", "POST", "PUT", "PATCH", "DELETE", "OPTIONS"));
        // Authorization 必须放行: 前端每个请求都要带 Bearer token.
        // 不能写成 * —— 通配在「不带凭证」时虽可用, 但部分中间层对它的处理并不一致,
        // 显式列出来更好排查.
        config.setAllowedHeaders(List.of("Authorization", "Content-Type", "Accept", "X-Requested-With"));
        config.setAllowCredentials(false);
        // 预检请求的结果缓存 1 小时, 省掉每个请求前的一次 OPTIONS 往返
        config.setMaxAge(3600L);

        UrlBasedCorsConfigurationSource source = new UrlBasedCorsConfigurationSource();
        source.registerCorsConfiguration("/**", config);
        return source;
    }
}
