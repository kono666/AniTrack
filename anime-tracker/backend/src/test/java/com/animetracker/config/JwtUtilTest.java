package com.animetracker.config;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.env.MockEnvironment;

import static org.junit.jupiter.api.Assertions.*;

/**
 * JWT 工具类单元测试
 */
class JwtUtilTest {

    /** 测试用密钥, 长度 >= 32 字节 */
    private static final String TEST_SECRET = "this-is-a-test-secret-key-for-jwt-testing-2024!";

    private JwtUtil jwtUtil;

    /**
     * 造一个只有 profile 有意义的假 Environment.
     *
     * 密钥校验会看当前 profile, 所以测试必须能控制它 —— 这也是把 Environment
     * 作为参数传进来而不是让 JwtUtil 自己去取的好处: 依赖显式化才好测.
     */
    private static MockEnvironment env(String... profiles) {
        MockEnvironment e = new MockEnvironment();
        e.setActiveProfiles(profiles);
        return e;
    }

    @BeforeEach
    void setUp() {
        // 使用一个足够长的密钥(>=32字节)
        jwtUtil = new JwtUtil(TEST_SECRET, 3600000L, env("dev"));
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

    // 原先这里还有 shouldExtractUsernameFromToken / shouldExtractRoleFromToken,
    // 随被删的两个方法一起去掉了 —— 它们钉的是"能读出 token 里的 username/role",
    // 而这件事现在已经不做(只取 userId, 权限现查库, 见 JwtUtil 里的说明).

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
        // 短密钥也能工作 (会被 SHA-256 扩展), 但会留下一条警告日志
        JwtUtil shortKeyUtil = new JwtUtil("short", 3600000L, env("dev"));
        String token = shortKeyUtil.generateToken(1L, "user", "USER");
        assertNotNull(token);
        assertTrue(shortKeyUtil.validateToken(token));
    }

    // ── 启动期密钥校验 ─────────────────────────────────────────
    // 这一组测试钉住的是「忘了配密钥」这个事故: 它不会以报错的形式出现,
    // 而是以「服务正常运行, 但任何人都能登录成管理员」的形式出现.

    @Test
    void shouldRejectBlankSecret() {
        IllegalStateException ex = assertThrows(IllegalStateException.class,
                () -> new JwtUtil("", 3600000L, env("postgres")));
        assertTrue(ex.getMessage().contains("未配置 JWT 密钥"),
                "错误信息应说清是缺少密钥, 否则排查时只能靠猜");
    }

    @Test
    void shouldRejectNullSecret() {
        assertThrows(IllegalStateException.class,
                () -> new JwtUtil(null, 3600000L, env("postgres")));
    }

    @Test
    void shouldRejectDevFallbackSecretOutsideDevProfile() {
        IllegalStateException ex = assertThrows(IllegalStateException.class,
                () -> new JwtUtil(JwtUtil.DEV_FALLBACK_SECRET, 3600000L, env("postgres")));
        assertTrue(ex.getMessage().contains("开发环境的默认 JWT 密钥"));
        assertTrue(ex.getMessage().contains("postgres"),
                "错误信息要带上当前 profile, 否则不知道该改哪里");
    }

    @Test
    void shouldRejectDevFallbackSecretWhenNoProfileActive() {
        // 没有显式指定 profile 时也必须拒绝: 这正是「部署时忘了设 profile」的场景
        assertThrows(IllegalStateException.class,
                () -> new JwtUtil(JwtUtil.DEV_FALLBACK_SECRET, 3600000L, env()));
    }

    @Test
    void shouldAllowDevFallbackSecretInDevProfile() {
        // 本地开发保留兜底值, 否则每次 clone 下来都要先配密钥才能启动
        JwtUtil util = new JwtUtil(JwtUtil.DEV_FALLBACK_SECRET, 3600000L, env("dev"));
        String token = util.generateToken(1L, "local", "USER");
        assertTrue(util.validateToken(token));
    }

    @Test
    void shouldAcceptProvidedSecretInAnyProfile() {
        // 显式提供了足够强的密钥, 在任何 profile 下都应放行
        JwtUtil util = new JwtUtil(TEST_SECRET, 3600000L, env("postgres"));
        assertTrue(util.validateToken(util.generateToken(1L, "ops", "ADMIN")));
    }
}
