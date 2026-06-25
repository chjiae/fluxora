package io.fluxora.platform.runtime;

/**
 * Gateway 可读取的最小运行时配置 Scope。
 * 每个 Scope 独立版本化，禁止 Gateway 将不同版本的模型、价格、路由拼接使用。
 */
public enum RuntimeScopeType {
    AUTH_API_KEY,
    AUTH_USER,
    AUTH_TENANT,
    TENANT_MODEL_ROUTE,
    /** 仅含当前租户可执行模型编码与稳定创建时间的 OpenAI 目录快照。 */
    TENANT_MODEL_CATALOG,
    /** Gateway 专用敏感快照，保存运行时重加密密文，禁止作为普通路由或管理接口响应。 */
    UPSTREAM_CREDENTIAL
}
