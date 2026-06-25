package io.fluxora.gateway.relay.runtime;

import io.fluxora.gateway.relay.failure.FailureClassification;
import io.fluxora.gateway.relay.failure.FailureKind;
import io.fluxora.gateway.relay.failure.FailureScope;
import io.fluxora.gateway.relay.failure.UpstreamSignal;
import io.fluxora.gateway.relay.scheduling.DispatchPlan;
import java.time.Duration;
import java.time.Instant;
import java.util.Optional;

/**
 * 将失败分类映射为本地隔离与平台上报事实。
 * 本类不写 Redis、不写数据库、不决定是否重试；它只给出副作用的安全范围。
 */
public final class RuntimeIncidentMapper {
    public Optional<RuntimeIncident> map(String requestId, int attemptNo, DispatchPlan plan,
                                         FailureClassification classification, UpstreamSignal signal) {
        if (classification.scope() == FailureScope.CLIENT || classification.scope() == FailureScope.UNKNOWN) {
            return Optional.empty();
        }
        return Optional.of(new RuntimeIncident(requestId, attemptNo, plan, classification,
                ttl(classification), Instant.now(), signal == null ? null : signal.httpStatus()));
    }

    private Duration ttl(FailureClassification classification) {
        if (classification.kind() == FailureKind.AUTH_INVALID) return Duration.ofSeconds(60);
        if (classification.kind() == FailureKind.UPSTREAM_BILLING_EXHAUSTED) return Duration.ofSeconds(300);
        if (classification.kind() == FailureKind.RATE_LIMITED) {
            long retryAfterMs = classification.cooldownAdvice().retryAfterMs();
            return Duration.ofMillis(retryAfterMs > 0L ? retryAfterMs : 30_000L);
        }
        return Duration.ofSeconds(30);
    }
}
