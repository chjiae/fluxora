package io.fluxora.platform.security;

import io.jsonwebtoken.Claims;
import io.jsonwebtoken.Jwts;
import java.util.Date;
import javax.crypto.SecretKey;
import io.jsonwebtoken.security.Keys;
import java.nio.charset.StandardCharsets;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

@Component
public class JwtService {

    private final SecretKey key;
    private final long expirationMs;

    public JwtService(@Value("${fluxora.security.jwt.secret}") String secret,
                      @Value("${fluxora.security.jwt.expiration-ms}") long expirationMs) {
        this.key = Keys.hmacShaKeyFor(secret.getBytes(StandardCharsets.UTF_8));
        this.expirationMs = expirationMs;
    }

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

    public Claims parseToken(String token) {
        return Jwts.parser()
                .verifyWith(key)
                .build()
                .parseSignedClaims(token)
                .getPayload();
    }

    public boolean isTokenValid(String token) {
        try {
            parseToken(token);
            return true;
        } catch (Exception e) {
            return false;
        }
    }
}
