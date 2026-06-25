package io.fluxora.gateway.relay.runtime;

import io.fluxora.gateway.relay.failure.FailureClassification;
import io.fluxora.gateway.relay.scheduling.DispatchPlan;
import java.time.Duration;
import java.time.Instant;

/**
 * Gateway 本地隔离与可靠上报使用的脱敏故障事实。
 * 仅包含内部数字 ID、Scope 与分类结果，不包含 BaseUrl、凭证、正文或异常栈。
 */
public record RuntimeIncident(String requestId, int attemptNo, DispatchPlan plan,
                              FailureClassification classification, Duration localTtl,
                              Instant occurredAt, Integer httpStatus) {
    public String attemptId() { return plan.lease().attemptId(); }
}
