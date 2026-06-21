package io.fluxora.platform.security;

import io.jsonwebtoken.Claims;
import io.jsonwebtoken.Jwts;
import java.util.Date;
import javax.crypto.SecretKey;
import io.jsonwebtoken.security.Keys;
import java.nio.charset.StandardCharsets;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * JWT 令牌签发与校验服务。
 * 令牌包含 userId、username、scopeType、tenantId，使用 HMAC-SHA 签名。
 * 密钥和过期时间通过 fluxora.security.jwt.* 配置，环境变量可覆盖。
 */
@Component
public class JwtService {

    private final SecretKey key;
    private final long expirationMs;

    public JwtService(@Value("${fluxora.security.jwt.secret}") String secret,
                      @Value("${fluxora.security.jwt.expiration-ms}") long expirationMs) {
        this.key = Keys.hmacShaKeyFor(secret.getBytes(StandardCharsets.UTF_8));
        this.expirationMs = expirationMs;
    }

    /**
     * 签发 JWT，包含用户身份和归属信息。
     * tenantId 仅租户级用户有值，平台级用户为 null 不写入 claims。
     */
    public String generateToken(Long userId, String username, String scopeType, Long tenantId) {
        Date now = new Date();
        Date expiry = new Date(now.getTime() + expirationMs);

        var builder = Jwts.builder()
                .subject(userId.toString())
                .claim("username", username)
                .claim("scopeType", scopeType)
                .issuedAt(now)
                .expiration(expiry)
                .signWith(key);

        if (tenantId != null) {
            builder.claim("tenantId", tenantId);
        }

        return builder.compact();
    }

    /** 解析 JWT 令牌，校验签名和过期 */
    public Claims parseToken(String token) {
        return Jwts.parser()
                .verifyWith(key)
                .build()
                .parseSignedClaims(token)
                .getPayload();
    }

    /** 验证令牌是否有效（签名正确且未过期） */
    public boolean isTokenValid(String token) {
        try {
            parseToken(token);
            return true;
        } catch (Exception e) {
            return false;
        }
    }
}
