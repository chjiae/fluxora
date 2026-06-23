package io.fluxora.gateway.runtime;

/** Gateway 可读取的四类运行时 Scope；与 Platform 的投影契约保持一致。 */
public enum RuntimeScopeType {
    AUTH_API_KEY,
    AUTH_USER,
    AUTH_TENANT,
    TENANT_MODEL_ROUTE
}
