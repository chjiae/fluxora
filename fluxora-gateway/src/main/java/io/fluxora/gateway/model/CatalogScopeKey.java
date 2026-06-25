package io.fluxora.gateway.model;

/** 模型目录 Scope Key 与 Platform RuntimeScope 的稳定契约，禁止拼接模型或上游信息。 */
public final class CatalogScopeKey {
    private CatalogScopeKey() {
    }

    public static String of(long tenantId, String inboundProtocol) {
        return tenantId + ":" + inboundProtocol;
    }
}
