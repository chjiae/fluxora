package io.fluxora.platform.runtime.availability;

import java.time.Instant;

/** 单个运行时资源状态更新；scopeKey 只使用安全 ID 或 tenantId 前缀的逻辑分组。 */
public record RuntimeResourceStateUpdate(long tenantId, String scopeType, String scopeKey, String runtimeState,
                                         String lastFailureKind, Instant lastFailedAt, Instant cooldownUntil) {
}
