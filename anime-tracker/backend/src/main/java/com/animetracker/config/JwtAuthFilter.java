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
            var auth = new UsernamePasswordAuthenticationToken(
                    user, null,
                    List.of(new SimpleGrantedAuthority("ROLE_" + user.getRole())));
            SecurityContextHolder.getContext().setAuthentication(auth);
        }
        // 无 Token 继续链(公开端点由 SecurityConfig 处理)

        filterChain.doFilter(request, response);
    }

    private String extractToken(HttpServletRequest request) {
        String bearer = request.getHeader("Authorization");
        if (StringUtils.hasText(bearer) && bearer.startsWith("Bearer ")) {
            return bearer.substring(7);
        }
        return null;
    }
}
