package com.animetracker.config;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * JWT 工具类单元测试
 */
class JwtUtilTest {

    private JwtUtil jwtUtil;

    @BeforeEach
    void setUp() {
        // 使用一个足够长的密钥(>=32字节)
        jwtUtil = new JwtUtil(
            "this-is-a-test-secret-key-for-jwt-testing-2024!",
            3600000L // 1小时
        );
    }

    @Test
    void shouldGenerateAndValidateToken() {
        String token = jwtUtil.generateToken(1L, "testuser", "USER");
        assertNotNull(token);
        assertFalse(token.isEmpty());
        assertTrue(jwtUtil.validateToken(token));
    }

    @Test
    void shouldExtractUserIdFromToken() {
        String token = jwtUtil.generateToken(42L, "alice", "USER");
        assertEquals(42L, jwtUtil.getUserIdFromToken(token));
    }

    @Test
    void shouldExtractUsernameFromToken() {
        String token = jwtUtil.generateToken(1L, "bob", "USER");
        assertEquals("bob", jwtUtil.getUsernameFromToken(token));
    }

    @Test
    void shouldExtractRoleFromToken() {
        String token = jwtUtil.generateToken(1L, "admin", "ADMIN");
        assertEquals("ADMIN", jwtUtil.getRoleFromToken(token));
    }

    @Test
    void shouldRejectInvalidToken() {
        assertFalse(jwtUtil.validateToken("invalid.token.here"));
        assertFalse(jwtUtil.validateToken(""));
        assertFalse(jwtUtil.validateToken(null));
    }

    @Test
    void shouldRejectTamperedToken() {
        String token = jwtUtil.generateToken(1L, "user", "USER");
        // 篡改 token 中间部分
        String tampered = token.substring(0, token.length() - 3) + "xxx";
        assertFalse(jwtUtil.validateToken(tampered));
    }

    @Test
    void shouldGenerateTokenWithShortSecret() {
        // 短密钥也能工作 (会被 SHA-256 扩展)
        JwtUtil shortKeyUtil = new JwtUtil("short", 3600000L);
        String token = shortKeyUtil.generateToken(1L, "user", "USER");
        assertNotNull(token);
        assertTrue(shortKeyUtil.validateToken(token));
    }
}
