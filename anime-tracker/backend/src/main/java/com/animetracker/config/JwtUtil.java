package com.animetracker.config;

import io.jsonwebtoken.*;
import io.jsonwebtoken.security.Keys;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;

import javax.crypto.SecretKey;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.Arrays;
import java.util.Date;

@Component
public class JwtUtil {

    private static final Logger log = LoggerFactory.getLogger(JwtUtil.class);

    /**
     * 开发环境兜底密钥, 必须与 application.yml 中 dev profile 下的值一致.
     *
     * 为什么要在代码里认出这个字符串: 配置文件无法表达「这个值只准在本地用」.
     * 真正危险的不是本地懒得配密钥, 而是部署时忘了配 —— 服务照常启动、
     * 功能全部正常、日志里没有任何异常, 只有攻击者知道门没锁.
     * 所以把这个判断放进代码, 让它在启动阶段就炸出来.
     */
    public static final String DEV_FALLBACK_SECRET = "AniTrack-Dev-Only-Secret-Change-In-Production!!";

    /** HMAC-SHA256 要求密钥至少 32 字节, 短于此长度签名强度被削弱 */
    private static final int MIN_SECRET_BYTES = 32;

    private final SecretKey key;

    private final long expiration;

    public JwtUtil(
            @Value("${jwt.secret:}") String secret,
            @Value("${jwt.expiration:604800000}") long expiration,
            Environment env) {
        validateSecret(secret, env);

        // 确保密钥至少256位 (32字节)
        byte[] keyBytes = secret.getBytes(StandardCharsets.UTF_8);
        if (keyBytes.length < MIN_SECRET_BYTES) {
            // 不足32字节时用SHA-256哈希扩展
            try {
                keyBytes = MessageDigest.getInstance("SHA-256").digest(keyBytes);
            } catch (NoSuchAlgorithmException e) {
                throw new RuntimeException("SHA-256 not available", e);
            }
        }
        this.key = Keys.hmacShaKeyFor(keyBytes);
        this.expiration = expiration;
    }

    /**
     * 启动期校验密钥 —— 违反约束时直接抛异常, 让 Spring 上下文加载失败.
     *
     * 这里的取舍很明确: 宁可服务起不来, 也不要带着一个「任何人都能伪造
     * 登录凭证」的密钥对外服务. 启动失败是几分钟就能修好的故障,
     * 密钥泄露则不是.
     */
    private static void validateSecret(String secret, Environment env) {
        String[] active = env.getActiveProfiles();
        boolean devProfile = Arrays.asList(active).contains("dev");

        if (secret == null || secret.isBlank()) {
            throw new IllegalStateException(
                    "未配置 JWT 密钥, 应用拒绝启动。\n"
                            + "  原因: jwt.secret 为空, 无法可靠地为 token 签名。\n"
                            + "  解决: 生成一个随机密钥并设为环境变量\n"
                            + "        openssl rand -base64 48\n"
                            + "        然后设置 JWT_SECRET=<上面生成的字符串>\n"
                            + "  说明: 刻意不提供兜底密钥 —— 兜底值一旦公开在仓库里, \n"
                            + "        任何人都能用它伪造出管理员身份的 token。");
        }

        if (DEV_FALLBACK_SECRET.equals(secret)) {
            if (!devProfile) {
                throw new IllegalStateException(
                        "检测到正在使用开发环境的默认 JWT 密钥, 应用拒绝启动。\n"
                                + "  当前 profile: "
                                + (active.length == 0 ? "(未指定)" : String.join(", ", active)) + "\n"
                                + "  原因: 这个密钥公开写在仓库里, 用它签发的 token 任何人都能伪造。\n"
                                + "  解决: 设置环境变量 JWT_SECRET=<openssl rand -base64 48 的输出>\n"
                                + "  说明: 如果这确实是本地开发, 请显式启用 dev profile。");
            }
            log.warn("JWT 正在使用开发环境默认密钥 —— 仅限本地开发, 切勿用于任何对外环境");
        } else if (secret.getBytes(StandardCharsets.UTF_8).length < MIN_SECRET_BYTES) {
            log.warn("JWT 密钥长度不足 {} 字节, 签名强度被削弱; "
                    + "建议改用 openssl rand -base64 48 生成", MIN_SECRET_BYTES);
        }
    }

    /** 生成 Token */
    public String generateToken(Long userId, String username, String role) {
        return Jwts.builder()
                .subject(String.valueOf(userId))
                .claim("username", username)
                .claim("role", role)
                .issuedAt(new Date())
                .expiration(new Date(System.currentTimeMillis() + expiration))
                .signWith(key)
                .compact();
    }

    /** 从 Token 提取 userId */
    public Long getUserIdFromToken(String token) {
        return Long.valueOf(parseClaims(token).getSubject());
    }

    /**
     * 从 Token 提取签发时刻.
     *
     * <p>唯一的用途是「改密之后旧 token 立即失效」那条判断(见 {@code JwtAuthFilter})。
     *
     * <p><b>注意精度</b>: 这个值是**秒**级的(JWT 规范里 iat 是 NumericDate, 也就是
     * 以秒为单位的 epoch), 而库里的 {@code password_changed_at} 是微秒。两边的比较
     * 必须先把微秒截掉, 否则同一秒内「改密 → 立刻用新 token」会被误拒 ——
     * 而那恰恰是常态, 不是边界情况。
     *
     * <p>返回 {@code Instant} 而不是 {@code Date}: 比较的另一侧是从
     * {@code LocalDateTime} 换算过来的, 中途经过 {@code Date} 只会再多一次无损转换。
     */
    public Instant getIssuedAtFromToken(String token) {
        Date issuedAt = parseClaims(token).getIssuedAt();
        return issuedAt == null ? null : issuedAt.toInstant();
    }

    // 这里原本还有 getUsernameFromToken / getRoleFromToken 两个方法, 已删.
    // 它们只被 JwtUtilTest 调用, 生产代码一处都没有 —— 而"从 token 里读角色"
    // 本身是个有风险的写法: token 里的 role 是签发那一刻的快照, 管理员被降级
    // 之后那张 token 仍然写着 ADMIN. 现在只从 token 取 userId, 权限每次现查库
    // (见 JwtAuthFilter: 拿 userId 查 user 再用库里的 role 建 Authority).
    // 留着这两个方法, 等于留了一条"看起来很方便"的旁路.

    /** 验证 Token 是否有效 */
    public boolean validateToken(String token) {
        try {
            parseClaims(token);
            return true;
        } catch (JwtException | IllegalArgumentException e) {
            return false;
        }
    }

    private Claims parseClaims(String token) {
        return Jwts.parser()
                .verifyWith(key)
                .build()
                .parseSignedClaims(token)
                .getPayload();
    }
}
