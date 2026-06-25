package io.fluxora.gateway.relay.runtime;

import io.fluxora.gateway.relay.failure.FailureScope;
import io.fluxora.gateway.relay.scheduling.DispatchCandidate;
import java.time.Instant;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 单 Gateway 进程内的短 TTL 隔离层，用于缩短 Platform Snapshot 传播窗口。
 * 它不是最终事实来源；过期后自动恢复，长期状态仍以 Platform 投影后的 Redis Snapshot 为准。
 */
public final class LocalRuntimeQuarantine implements RuntimeAvailabilitySnapshot {
    private final Map<String, Instant> quarantinedUntil = new ConcurrentHashMap<>();

    public void apply(RuntimeIncident incident) {
        Instant until = incident.occurredAt().plus(incident.localTtl());
        key(incident.classification().scope(), incident).ifPresent(key -> quarantinedUntil.put(key, until));
    }

    @Override
    public boolean accepts(DispatchCandidate candidate) {
        Instant now = Instant.now();
        return available("CREDENTIAL:" + candidate.providerCredentialId(), now)
                && available("PROVIDER_CHANNEL_CREDENTIAL:" + candidate.providerChannelCredentialId(), now)
                && available("BILLING_ACCOUNT_GROUP:" + candidate.billingAccountGroup(), now)
                && available("QUOTA_SCOPE:" + candidate.quotaScope(), now)
                && available("ROUTE_TARGET:" + candidate.routeTargetId(), now)
                && available("PROVIDER_CHANNEL_MODEL:" + candidate.providerChannelModelId(), now)
                && available("PROVIDER_CHANNEL:" + candidate.providerChannelId(), now);
    }

    private boolean available(String key, Instant now) {
        Instant until = quarantinedUntil.get(key);
        if (until == null) return true;
        if (until.isAfter(now)) return false;
        quarantinedUntil.remove(key, until);
        return true;
    }

    private java.util.Optional<String> key(FailureScope scope, RuntimeIncident incident) {
        return switch (scope) {
            case CREDENTIAL -> java.util.Optional.of("CREDENTIAL:" + incident.plan().providerCredentialId());
            case PROVIDER_CHANNEL_CREDENTIAL -> java.util.Optional.of(
                    "PROVIDER_CHANNEL_CREDENTIAL:" + incident.plan().providerChannelCredentialId());
            case BILLING_ACCOUNT_GROUP -> java.util.Optional.of(
                    "BILLING_ACCOUNT_GROUP:" + incident.plan().billingAccountGroup());
            case QUOTA_SCOPE -> java.util.Optional.of("QUOTA_SCOPE:" + incident.plan().quotaScope());
            case ROUTE_TARGET -> java.util.Optional.of("ROUTE_TARGET:" + incident.plan().routeTargetId());
            case PROVIDER_CHANNEL_MODEL -> java.util.Optional.of(
                    "PROVIDER_CHANNEL_MODEL:" + incident.plan().providerChannelModelId());
            case PROVIDER_CHANNEL -> java.util.Optional.of("PROVIDER_CHANNEL:" + incident.plan().providerChannelId());
            case CLIENT, UNKNOWN -> java.util.Optional.empty();
        };
    }
}
