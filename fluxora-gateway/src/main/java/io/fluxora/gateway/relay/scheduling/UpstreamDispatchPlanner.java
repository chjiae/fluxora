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
        // 选取最高可用优先级层（数值越小优先级越高），流量优先落在更高优先级的 Tier
        int highestPriorityTier = Integer.MAX_VALUE;
        for (DispatchCandidate candidate : candidates) {
            highestPriorityTier = Math.min(highestPriorityTier, candidate.priorityTier());
        }
        // 只保留当前最高优先级 Tier 的候选目标，低优先级 Tier 仅在当前层无可用候选时才会在下一轮被选中
        List<DispatchCandidate> tierCandidates = new ArrayList<>();
        for (DispatchCandidate candidate : candidates) {
            if (candidate.priorityTier() == highestPriorityTier) {
                tierCandidates.add(candidate);
            }
        }
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
        // 计算各 Channel 当前活跃连接数的最小值，用于筛选"最空闲"的 Channel
        long minActive = 0L;
        boolean first = true;
        for (Long channelId : channelIds) {
            long active = leaseManager.activeCount("channel", Long.toString(channelId));
            if (first || active < minActive) {
                minActive = active;
                first = false;
            }
        }
        // 收集所有活跃数等于最小值的 Channel，使流量在并列最空闲的 Channel 间轮询
        List<Long> leastActive = new ArrayList<>();
        for (Long channelId : channelIds) {
            if (leaseManager.activeCount("channel", Long.toString(channelId)) == minActive) {
                leastActive.add(channelId);
            }
        }
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
        // 计算各凭证当前活跃连接数的最小值，用于筛选"最空闲"的凭证
        long minActive = 0L;
        boolean first = true;
        for (DispatchCandidate credential : credentials) {
            long active = leaseManager.activeCount("credential", Long.toString(credential.providerCredentialId()));
            if (first || active < minActive) {
                minActive = active;
                first = false;
            }
        }
        // 收集所有活跃数等于最小值的凭证，使流量在并列最空闲的凭证间轮询
        List<DispatchCandidate> leastActive = new ArrayList<>();
        for (DispatchCandidate credential : credentials) {
            if (leaseManager.activeCount("credential", Long.toString(credential.providerCredentialId())) == minActive) {
                leastActive.add(credential);
            }
        }
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
