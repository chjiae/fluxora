package io.fluxora.platform.runtime;

import java.time.Instant;

/**
 * 运行时 Outbox 事件实体。业务服务只记录来源实体与操作，影响 Scope 由 RuntimeImpactResolver 集中计算。
 */
public record RuntimeOutboxEvent(Long id, Long tenantId, String aggregateType, Long aggregateId,
                                 String mutationType, String impactHint, int attemptCount,
                                 Instant occurredAt) {
}
