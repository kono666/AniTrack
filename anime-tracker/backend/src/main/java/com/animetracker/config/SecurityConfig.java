package com.animetracker.config;

import jakarta.servlet.http.HttpServletResponse;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.Environment;
import org.springframework.http.HttpMethod;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
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

    private final JwtAuthFilter jwtAuthFilter;

    public SecurityConfig(JwtAuthFilter jwtAuthFilter) {
        this.jwtAuthFilter = jwtAuthFilter;
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
                "/api/stats/anime-heat"
        ));
        if (devProfile) {
            publicPaths.addAll(List.of(
                    "/h2-console/**",
                    "/swagger-ui/**",
                    "/swagger-ui.html",
                    "/v3/api-docs/**"
            ));
        }

        http
                .cors(cors -> cors.configurationSource(corsConfigurationSource()))
                .csrf(csrf -> csrf.disable())
                .sessionManagement(sm -> sm.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .authorizeHttpRequests(auth -> auth
                        // 公开接口
                        .requestMatchers(publicPaths.toArray(new String[0])).permitAll()
                        // AI 对话对访客开放: 未登录时工具注册表只会放出公开工具, 不存在越权路径.
                        // 开放是因为「不用注册就能试」对作品展示很重要, 由限流负责成本兜底.
                        .requestMatchers(HttpMethod.POST,
                                "/api/agent/chat", "/api/agent/chat/stream").permitAll()
                        .requestMatchers(HttpMethod.GET, "/api/agent/info").permitAll()
                        // 管理员接口
                        .requestMatchers("/api/admin/**").hasRole("ADMIN")
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

    @Bean
    public CorsConfigurationSource corsConfigurationSource() {
        CorsConfiguration config = new CorsConfiguration();
        config.setAllowedOriginPatterns(List.of("*"));
        config.setAllowedMethods(List.of("*"));
        config.setAllowedHeaders(List.of("*"));
        config.setAllowCredentials(true);

        UrlBasedCorsConfigurationSource source = new UrlBasedCorsConfigurationSource();
        source.registerCorsConfiguration("/**", config);
        return source;
    }
}
