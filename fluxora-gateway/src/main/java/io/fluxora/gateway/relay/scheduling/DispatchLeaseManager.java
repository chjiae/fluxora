package io.fluxora.gateway.relay.scheduling;

import io.vertx.core.Future;

/** 租约接口；实现可为 Redis 原子租约，也可在无硬限制时按配置降级为本机近似。 */
public interface DispatchLeaseManager {
    Future<DispatchLease> acquire(DispatchCandidate candidate, String requestId, String attemptId);
    Future<Void> release(DispatchLease lease);
    default long activeCount(String resourceType, String resourceId) { return 0L; }
}
