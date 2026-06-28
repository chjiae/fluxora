package io.fluxora.platform.security;

import io.fluxora.common.error.BusinessErrorCode;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * Gateway 内部结算接口的最小服务认证：时间戳 + requestId nonce + HMAC。
 * requestId 同时受数据库唯一约束保护，捕获后的完全重放最多得到同一幂等结算结果。
 */
@Component
public class InternalGatewayAuthenticationFilter extends OncePerRequestFilter {
    private static final long MAX_CLOCK_SKEW_MS = 60_000L;
    private final byte[] secret;

    public InternalGatewayAuthenticationFilter(
            @Value("${fluxora.security.internal-gateway.hmac-secret}") String secret) {
        this.secret = secret.getBytes(StandardCharsets.UTF_8);
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        return !request.getRequestURI().startsWith("/internal/gateway/");
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        String requestId = request.getHeader("X-Fluxora-Internal-Request-Id");
        String timestamp = request.getHeader("X-Fluxora-Internal-Timestamp");
        String signature = request.getHeader("X-Fluxora-Internal-Signature");
        if (!valid(request, requestId, timestamp, signature)) {
            SecurityExceptionHandler.writeErrorResponse(response, 401, BusinessErrorCode.AUTH_INVALID_CREDENTIALS);
            return;
        }
        chain.doFilter(request, response);
    }

    private boolean valid(HttpServletRequest request, String requestId, String timestamp, String signature) {
        if (requestId == null || requestId.isBlank() || requestId.length() > 64 || timestamp == null || signature == null) {
            return false;
        }
        try {
            long value = Long.parseLong(timestamp);
            if (Math.abs(System.currentTimeMillis() - value) > MAX_CLOCK_SKEW_MS) return false;
            String canonical = request.getMethod() + "\n" + request.getRequestURI() + "\n" + timestamp + "\n" + requestId;
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(secret, "HmacSHA256"));
            byte[] expected = mac.doFinal(canonical.getBytes(StandardCharsets.UTF_8));
            byte[] supplied = java.util.HexFormat.of().parseHex(signature);
            return MessageDigest.isEqual(expected, supplied);
        } catch (Exception ignored) {
            return false;
        }
    }
}
