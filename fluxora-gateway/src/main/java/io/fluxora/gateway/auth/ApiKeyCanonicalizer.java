package io.fluxora.gateway.auth;

import java.util.Optional;
import java.util.regex.Pattern;

/** 在访问 Redis 前完成 API Key 的低成本格式校验，格式明显非法时直接失败关闭。 */
public final class ApiKeyCanonicalizer {
    private static final Pattern API_KEY = Pattern.compile("flx_[A-Za-z0-9_-]{8}_[A-Za-z0-9_-]{32}");

    private ApiKeyCanonicalizer() {}

    public static Optional<String> canonicalize(String raw) {
        return raw != null && API_KEY.matcher(raw).matches() ? Optional.of(raw) : Optional.empty();
    }
}
