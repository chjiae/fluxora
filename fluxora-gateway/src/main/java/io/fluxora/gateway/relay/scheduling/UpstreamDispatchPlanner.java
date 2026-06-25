package io.fluxora.gateway.relay.scheduling;

import io.fluxora.common.runtime.RouteExecutionEligibility;
import io.fluxora.gateway.GatewayFailure;
import io.fluxora.gateway.relay.runtime.RuntimeAvailabilitySnapshot;
import io.fluxora.gateway.route.RouteSelection;
import io.vertx.core.Future;
import io.vertx.core.json.JsonArray;
import io.vertx.core.json.JsonObject;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 分层调度器：先选最高可用 Priority Tier，再按 Channel 份额调度，最后在目标内选择
 * quotaScope / billingAccountGroup / Credential。它不解析失败、不判断重试、不触碰余额。
 */
public final class UpstreamDispatchPlanner {
    private final DispatchLeaseManager leaseManager;
    private final RuntimeAvailabilitySnapshot availability;
    private final Map<String, Integer> cursors = new HashMap<>();

    public UpstreamDispatchPlanner(DispatchLeaseManager leaseManager) {
        this(leaseManager, RuntimeAvailabilitySnapshot.ALWAYS_AVAILABLE);
    }

    public UpstreamDispatchPlanner(DispatchLeaseManager leaseManager, RuntimeAvailabilitySnapshot availability) {
        this.leaseManager = leaseManager;
        this.availability = availability == null ? RuntimeAvailabilitySnapshot.ALWAYS_AVAILABLE : availability;
    }

    public Future<DispatchPlan> planAndAcquire(JsonObject routeSnapshot, DispatchExclusions exclusions,
                                               String requestId, String attemptId) {
        List<DispatchCandidate> candidates = candidates(routeSnapshot, exclusions == null ? DispatchExclusions.none() : exclusions);
        if (candidates.isEmpty()) return Future.failedFuture(GatewayFailure.modelUnavailable());
        int tier = candidates.stream().map(DispatchCandidate::priorityTier).min(Comparator.naturalOrder()).orElse(Integer.MAX_VALUE);
        List<DispatchCandidate> tierCandidates = candidates.stream().filter(c -> c.priorityTier() == tier).toList();
        DispatchCandidate selected = selectCredential(selectChannel(tierCandidates), exclusions == null ? DispatchExclusions.none() : exclusions);
        return leaseManager.acquire(selected, requestId, attemptId).map(lease -> new DispatchPlan(
                new RouteSelection(selected.routeTargetId(), selected.providerChannelId(), selected.providerChannelModelId(),
                        selected.target().getString("outboundProtocol"), selected.target().getString("upstreamModelId"),
                        selected.target().copy(), routeSnapshot),
                selected.credentialRef().copy(), lease, selected.priorityTier(), "WEIGHTED_LEAST_ACTIVE", "REDIS_OR_LOCAL"));
    }

    public Future<Void> release(DispatchLease lease) { return leaseManager.release(lease); }

    private DispatchCandidate selectChannel(List<DispatchCandidate> candidates) {
        Map<Long, List<DispatchCandidate>> byChannel = new HashMap<>();
        for (DispatchCandidate candidate : candidates) byChannel.computeIfAbsent(candidate.providerChannelId(), ignored -> new ArrayList<>()).add(candidate);
        List<Long> channelIds = new ArrayList<>(byChannel.keySet());
        channelIds.sort(Comparator.naturalOrder());
        long minActive = channelIds.stream()
                .mapToLong(id -> leaseManager.activeCount("channel", Long.toString(id))).min().orElse(0L);
        List<Long> leastActive = channelIds.stream()
                .filter(id -> leaseManager.activeCount("channel", Long.toString(id)) == minActive).toList();
        long selectedChannel = leastActive.get(nextCursor("channel:" + candidates.getFirst().priorityTier(), leastActive.size()));
        return byChannel.get(selectedChannel).get(nextCursor("target:" + selectedChannel, byChannel.get(selectedChannel).size()));
    }

    private DispatchCandidate selectCredential(DispatchCandidate selectedTarget, DispatchExclusions exclusions) {
        JsonArray refs = selectedTarget.target().getJsonArray("credentialRefs", new JsonArray());
        List<DispatchCandidate> credentials = new ArrayList<>();
        for (int i = 0; i < refs.size(); i++) {
            JsonObject ref = refs.getJsonObject(i);
            if (ref == null) continue;
            DispatchCandidate candidate = candidate(selectedTarget.target(), ref, selectedTarget.priorityTier());
            if (exclusions.accepts(candidate)) credentials.add(candidate);
        }
        if (credentials.isEmpty()) throw GatewayFailure.modelUnavailable();
        long minActive = credentials.stream()
                .mapToLong(c -> leaseManager.activeCount("credential", Long.toString(c.providerCredentialId()))).min().orElse(0L);
        List<DispatchCandidate> leastActive = credentials.stream()
                .filter(c -> leaseManager.activeCount("credential", Long.toString(c.providerCredentialId())) == minActive).toList();
        return leastActive.get(nextCursor("credential:" + selectedTarget.routeTargetId(), leastActive.size()));
    }

    private List<DispatchCandidate> candidates(JsonObject routeSnapshot, DispatchExclusions exclusions) {
        JsonArray targets = routeSnapshot.getJsonArray("targets", new JsonArray());
        List<DispatchCandidate> result = new ArrayList<>();
        for (int i = 0; i < targets.size(); i++) {
            JsonObject target = targets.getJsonObject(i);
            if (!targetCallable(target)) continue;
            JsonArray refs = target.getJsonArray("credentialRefs", new JsonArray());
            for (int j = 0; j < refs.size(); j++) {
                JsonObject ref = refs.getJsonObject(j);
                if (ref == null) continue;
                DispatchCandidate candidate = candidate(target, ref, target.getInteger("priority", Integer.MAX_VALUE));
                if (exclusions.accepts(candidate) && availability.accepts(candidate)
                        && leaseManager.activeCount("credential", Long.toString(candidate.providerCredentialId()))
                        < candidate.maxConcurrentStreams()) {
                    result.add(candidate);
                }
            }
        }
        return result;
    }

    private DispatchCandidate candidate(JsonObject target, JsonObject ref, int priorityTier) {
        long credentialId = ref.getLong("providerCredentialId", -1L);
        return new DispatchCandidate(target, ref, priorityTier, Math.max(1, target.getInteger("weight", 1)),
                target.getLong("routeTargetId"), target.getLong("providerChannelId"),
                target.getLong("providerChannelModelId"), ref.getLong("providerChannelCredentialId", credentialId),
                credentialId, ref.getString("quotaScope", "credential:" + credentialId),
                ref.getString("billingAccountGroup", "credential:" + credentialId),
                Math.max(1, ref.getInteger("trafficWeight", 1)), ref.getInteger("maxConcurrentStreams", Integer.MAX_VALUE));
    }

    private boolean targetCallable(JsonObject target) {
        return target != null && RouteExecutionEligibility.targetCallable(
                "ENABLED".equals(target.getString("targetStatus")),
                "ENABLED".equals(target.getString("mappingStatus")),
                "ENABLED".equals(target.getString("candidateStatus")),
                "ENABLED".equals(target.getString("channelStatus")),
                target.getBoolean("hasUsableCredential", false),
                target.getString("outboundProtocol"), target.getString("outboundProtocol"),
                target.getInteger("priority"), target.getInteger("weight"), target.getLong("routeTargetId"),
                target.getLong("providerChannelId"), target.getLong("providerChannelModelId"));
    }

    private int nextCursor(String key, int size) {
        if (size <= 1) return 0;
        int next = cursors.getOrDefault(key, 0);
        cursors.put(key, (next + 1) % size);
        return next % size;
    }
}
