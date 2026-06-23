package io.fluxora.gateway.auth;

import java.nio.charset.StandardCharsets;
import java.util.HexFormat;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

/** 完整 canonical API Key 的 HMAC-SHA-256；输入和 Secret 均不进入日志、缓存值或异常消息。 */
public final class ApiKeyLookupHasher {
    private static final String ALGORITHM = "HmacSHA256";
    private final byte[] secret;

    public ApiKeyLookupHasher(String secret) {
        if (secret == null || secret.isBlank()) {
            throw new IllegalArgumentException("API Key Lookup Secret 未配置");
        }
        this.secret = secret.getBytes(StandardCharsets.UTF_8);
    }

    public String hash(String canonicalApiKey) {
        try {
            Mac mac = Mac.getInstance(ALGORITHM);
            mac.init(new SecretKeySpec(secret, ALGORITHM));
            return HexFormat.of().formatHex(mac.doFinal(canonicalApiKey.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception error) {
            throw new IllegalStateException("HMAC-SHA-256 不可用", error);
        }
    }
}
