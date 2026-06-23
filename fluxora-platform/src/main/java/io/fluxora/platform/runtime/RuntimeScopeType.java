package io.fluxora.platform.runtime;

/**
 * Gateway 可读取的最小运行时配置 Scope。
 * 每个 Scope 独立版本化，禁止 Gateway 将不同版本的模型、价格、路由拼接使用。
 */
public enum RuntimeScopeType {
    AUTH_API_KEY,
    AUTH_USER,
    AUTH_TENANT,
    TENANT_MODEL_ROUTE
}
