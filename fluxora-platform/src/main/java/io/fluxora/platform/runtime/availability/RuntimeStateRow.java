package io.fluxora.platform.runtime.availability;

import java.time.Instant;

/**
 * 运行时故障状态查询行。同一 SQL 联表查出 scope 关联资源的可读名称，
 * 前端直接展示，不需 N+1 逐条反查。
 */
public record RuntimeStateRow(
        long tenantId,
        String scopeType,
        String scopeKey,
        /** AVAILABLE 不返回；列表只包含非 AVAILABLE 状态 */
        String runtimeState,
        String lastFailureKind,
        Instant lastFailedAt,
        Instant cooldownUntil,
        Instant updatedAt,
        /** 关联资源的可读标识：通道名 / 上游模型 ID / 凭证授权类型 / 计费组 / 配额域等 */
        String resourceLabel
) {
}
