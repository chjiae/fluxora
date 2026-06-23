package io.fluxora.platform.runtime.mapper;

/** MyBatis 投影行：用于把领域关联关系收敛为 TenantModelRoute 的最小 Scope。 */
public record RuntimeScopeRow(Long tenantId, String inboundProtocol, String tenantModelCode) {
}
