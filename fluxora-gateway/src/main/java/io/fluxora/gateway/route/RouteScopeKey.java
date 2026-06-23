package io.fluxora.gateway.route;

import java.nio.charset.StandardCharsets;
import java.util.Base64;

/** 与 Platform 一致的 TenantModelRoute Scope 编码，避免 Redis Key 中模型文本的分隔符歧义。 */
public final class RouteScopeKey {
    private RouteScopeKey() {}

    public static String of(long tenantId, String inboundProtocol, String tenantModelCode) {
        String encoded = Base64.getUrlEncoder().withoutPadding()
                .encodeToString(tenantModelCode.getBytes(StandardCharsets.UTF_8));
        return tenantId + ":" + inboundProtocol + ":" + encoded;
    }
}
