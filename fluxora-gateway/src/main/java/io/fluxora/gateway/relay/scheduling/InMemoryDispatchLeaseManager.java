package io.fluxora.gateway.relay.scheduling;

import io.vertx.core.Future;
import java.time.Instant;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/** 本机降级/单元测试租约；生产硬并发保护由 RedisDispatchLeaseManager 承担。 */
public final class InMemoryDispatchLeaseManager implements DispatchLeaseManager {
    private final Map<String, Long> active = new HashMap<>();

    @Override
    public synchronized Future<DispatchLease> acquire(DispatchCandidate candidate, String requestId, String attemptId) {
        String credentialKey = key("credential", Long.toString(candidate.providerCredentialId()));
        if (candidate.maxConcurrentStreams() > 0 && active.getOrDefault(credentialKey, 0L) >= candidate.maxConcurrentStreams()) {
            return Future.failedFuture("调度资源容量已满");
        }
        inc("channel", Long.toString(candidate.providerChannelId()));
        inc("quota", candidate.quotaScope());
        inc("credential", Long.toString(candidate.providerCredentialId()));
        DispatchLease lease = new DispatchLease(UUID.randomUUID().toString(), attemptId, candidate.routeTargetId(),
                candidate.providerChannelId(), candidate.quotaScope(), candidate.billingAccountGroup(),
                candidate.providerCredentialId(), Instant.now().plusSeconds(120));
        return Future.succeededFuture(lease);
    }

    @Override
    public synchronized Future<Void> release(DispatchLease lease) {
        if (lease == null) return Future.succeededFuture();
        dec("channel", Long.toString(lease.providerChannelId()));
        dec("quota", lease.quotaScope());
        dec("credential", Long.toString(lease.credentialId()));
        return Future.succeededFuture();
    }

    @Override
    public synchronized long activeCount(String resourceType, String resourceId) {
        return active.getOrDefault(key(resourceType, resourceId), 0L);
    }

    private void inc(String type, String id) { active.merge(key(type, id), 1L, Long::sum); }
    private void dec(String type, String id) { active.computeIfPresent(key(type, id), (k, v) -> v <= 1 ? null : v - 1); }
    private static String key(String type, String id) { return type + ":" + id; }
}
