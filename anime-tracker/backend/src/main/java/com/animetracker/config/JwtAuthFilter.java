package com.animetracker.config;

import com.animetracker.entity.User;
import com.animetracker.repository.UserRepository;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Optional;

@Component
public class JwtAuthFilter extends OncePerRequestFilter {

    private final JwtUtil jwtUtil;
    private final UserRepository userRepository;

    public JwtAuthFilter(JwtUtil jwtUtil, UserRepository userRepository) {
        this.jwtUtil = jwtUtil;
        this.userRepository = userRepository;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request,
                                    HttpServletResponse response,
                                    FilterChain filterChain)
            throws ServletException, IOException {

        String token = extractToken(request);

        if (token != null) {
            // 有 Token: 必须有效, 否则返回401
            if (!jwtUtil.validateToken(token)) {
                response.setContentType("application/json;charset=UTF-8");
                response.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
                response.getWriter().write("{\"code\":401,\"message\":\"Token无效或已过期\"}");
                return;
            }

            Long userId = jwtUtil.getUserIdFromToken(token);
            var userOpt = userRepository.findById(userId);

            if (userOpt.isEmpty() || !"ACTIVE".equals(userOpt.get().getStatus())) {
                response.setContentType("application/json;charset=UTF-8");
                response.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
                response.getWriter().write("{\"code\":401,\"message\":\"用户不存在或已禁用\"}");
                return;
            }

            var user = userOpt.get();

            // 「改密之前签发的 token 不再算数」这条**不在这里返 401**, 只是不认它 ——
            // 完整的理由见下面 isStaleAfterPasswordChange 的注释.
            if (!isStaleAfterPasswordChange(token, user)) {
                var auth = new UsernamePasswordAuthenticationToken(
                        user, null,
                        List.of(new SimpleGrantedAuthority("ROLE_" + user.getRole())));
                SecurityContextHolder.getContext().setAuthentication(auth);
            }
        }
        // 无 Token 继续链(公开端点由 SecurityConfig 处理)

        filterChain.doFilter(request, response);
    }

    /**
     * 这张 token 是不是「改密之前签发的」.
     *
     * <p><b>为什么需要这条判断.</b> 本仓的 JWT 是无状态、不带 jti 的, 改完密码之后旧
     * token 本来能一直用到 7 天过期 —— 于是「改密码」在服务端等于没发生, 而这件事的
     * 两个常见触发点(用户怀疑账号被盗、管理员强制重置)要的正是把别人手里那把钥匙作废。
     * 有了 {@code password_changed_at} 这一列, 每次请求多看一眼就能做到, 不用引黑名单。
     *
     * <p><b>精度: 必须先把微秒截掉.</b> JWT 的 {@code iat} 是**秒**级的, 而这一列是
     * {@code TIMESTAMP(6)}。不截的话, 「同一秒内改完密码、立刻拿响应里那张新 token 去调
     * 下一个接口」会被判成过期并踢下线 —— 而那恰恰是每条改密用例都会走到的**常态**,
     * 不是边界情况。截断之后同一秒内签发的 token {@code iat == 截断值}, 不满足
     * {@code isBefore}, 放行。
     *
     * <p>留下的窗口只有「改密那一秒之内签发的旧 token」—— 攻击者得恰好在这一秒里持有
     * 一张刚签发的 token, 而这个窗口是 1 秒、不可预测、且不给任何别的便利。
     *
     * <p><b>为什么这里不返 401, 只把身份摘掉。</b> 上面两条(签名/有效期不对、用户不存在
     * 或已禁用)都在过滤器里直接 401, 因为它们描述的是「这张凭证对谁都没用了」。
     * 这一条不一样: **用户是好的, 只是这张凭证旧了** —— 而带着旧凭证发出来的那次请求,
     * 很可能正是「用新密码重新登录」这一次。
     *
     * <p>在过滤器里 401 会把那条路堵死: 前端的请求拦截器只要有 token 就带上, 于是
     * 登录请求会带着那张旧 token 撞上 401, 用户被弹回登录页、再提交一次才能成功
     * (要到第一次 401 触发前端清登录态之后)。摘掉身份就没有这个问题 —— 登录/注册
     * 本来就是公开端点, 照常走到; 需要身份的接口由 {@code SecurityConfig} 那条
     * {@code anyRequest().authenticated()} 给出 401「请先登录」, 前端据此清登录态并跳转,
     * 行为与「会话过期」完全一致(这本来就是它该有的解释)。
     *
     * <p>{@code passwordChangedAt} 为空(从没改过密码)时一律放行 —— 不给存量用户制造一次
     * 全员掉线。
     */
    private boolean isStaleAfterPasswordChange(String token, User user) {
        LocalDateTime changedAt = user.getPasswordChangedAt();
        if (changedAt == null) {
            return false;
        }
        Instant issuedAt = jwtUtil.getIssuedAtFromToken(token);
        if (issuedAt == null) {
            return false;
        }
        Instant changedAtSecond =
                changedAt.truncatedTo(ChronoUnit.SECONDS).atZone(ZoneId.systemDefault()).toInstant();
        return issuedAt.isBefore(changedAtSecond);
    }

    private String extractToken(HttpServletRequest request) {
        String bearer = request.getHeader("Authorization");
        if (StringUtils.hasText(bearer) && bearer.startsWith("Bearer ")) {
            return bearer.substring(7);
        }
        return null;
    }
}
