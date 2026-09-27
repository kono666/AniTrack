package com.animetracker.config;

import io.jsonwebtoken.*;
import io.jsonwebtoken.security.Keys;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import javax.crypto.SecretKey;
import java.nio.charset.StandardCharsets;
import java.util.Date;

@Component
public class JwtUtil {

    private final SecretKey key;

    private final long expiration;

    public JwtUtil(
            @Value("${jwt.secret}") String secret,
            @Value("${jwt.expiration:604800000}") long expiration) {
        // 确保密钥至少256位 (32字节)
        byte[] keyBytes = secret.getBytes(StandardCharsets.UTF_8);
        if (keyBytes.length < 32) {
            // 不足32字节时用SHA-256哈希扩展
            try {
                java.security.MessageDigest md = java.security.MessageDigest.getInstance("SHA-256");
                keyBytes = md.digest(keyBytes);
            } catch (java.security.NoSuchAlgorithmException e) {
                throw new RuntimeException("SHA-256 not available", e);
            }
        }
        this.key = Keys.hmacShaKeyFor(keyBytes);
        this.expiration = expiration;
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

    /** 从 Token 提取用户名 */
    public String getUsernameFromToken(String token) {
        return parseClaims(token).get("username", String.class);
    }

    /** 从 Token 提取角色 */
    public String getRoleFromToken(String token) {
        return parseClaims(token).get("role", String.class);
    }

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
