package io.fluxora.platform.runtime;

import java.nio.charset.StandardCharsets;
import java.util.Base64;

/** 运行时最小一致性单元；scopeKey 只保存安全逻辑值，Redis Key 由存储层统一拼接。 */
public record RuntimeScope(RuntimeScopeType type, String scopeKey, Long tenantId,
                           String inboundProtocol, String tenantModelCode) {

    /** API Key Scope 仅使用不可逆 HMAC 摘要，绝不保存或拼接明文 Key。 */
    public static RuntimeScope apiKey(String lookupHash, Long tenantId) {
        return new RuntimeScope(RuntimeScopeType.AUTH_API_KEY, lookupHash, tenantId, null, null);
    }

    /** 用户状态独立于 API Key 投影，避免用户启停时扫描其全部 Key。 */
    public static RuntimeScope user(Long tenantId, Long userId) {
        return new RuntimeScope(RuntimeScopeType.AUTH_USER, tenantId + ":" + userId, tenantId, null, null);
    }

    /** 租户状态独立投影，Gateway 可在模型路由前快速失败关闭。 */
    public static RuntimeScope tenant(Long tenantId) {
        return new RuntimeScope(RuntimeScopeType.AUTH_TENANT, String.valueOf(tenantId), tenantId, null, null);
    }

    /**
     * 模型编码采用 URL-safe Base64 写入逻辑 Scope Key，避免分隔符冲突和非 ASCII Key 歧义。
     * 原始编码仅存在快照内容与失效事件中，不参与 Redis Key 的字符串拼接。
     */
    public static RuntimeScope route(Long tenantId, String inboundProtocol, String tenantModelCode) {
        String encodedCode = Base64.getUrlEncoder().withoutPadding()
                .encodeToString(tenantModelCode.getBytes(StandardCharsets.UTF_8));
        return new RuntimeScope(RuntimeScopeType.TENANT_MODEL_ROUTE,
                tenantId + ":" + inboundProtocol + ":" + encodedCode,
                tenantId, inboundProtocol, tenantModelCode);
    }
}
