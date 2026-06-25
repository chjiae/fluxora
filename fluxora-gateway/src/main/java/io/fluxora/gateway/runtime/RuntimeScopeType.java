package io.fluxora.gateway.runtime;

/** Gateway 可读取的四类运行时 Scope；与 Platform 的投影契约保持一致。 */
public enum RuntimeScopeType {
    AUTH_API_KEY,
    AUTH_USER,
    AUTH_TENANT,
    TENANT_MODEL_ROUTE,
    /** 当前租户 OpenAI 入站可执行模型的最小安全目录快照。 */
    TENANT_MODEL_CATALOG,
    /** Gateway 专用敏感 Scope，只缓存运行时密文快照，不缓存解密后的上游凭证。 */
    UPSTREAM_CREDENTIAL
}
