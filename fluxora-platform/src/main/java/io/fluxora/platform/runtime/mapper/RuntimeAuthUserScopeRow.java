package io.fluxora.platform.runtime.mapper;

/**
 * Redis 命名空间全量恢复时的用户鉴权 Scope。
 * 不能复用模型路由 DTO，避免把模型编码误当成用户 ID 而漏建 AUTH_USER 快照。
 */
public record RuntimeAuthUserScopeRow(Long tenantId, Long userId) {
}
