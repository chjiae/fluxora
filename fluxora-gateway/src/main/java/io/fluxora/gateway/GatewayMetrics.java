package io.fluxora.gateway;

import java.util.concurrent.atomic.AtomicLong;

/** 无敏感标签的轻量内部计数器；不记录 API Key、用户、模型文本或完整 Redis Key。 */
public final class GatewayMetrics {
    public final AtomicLong apiKeyL1Hit = new AtomicLong();
    public final AtomicLong userL1Hit = new AtomicLong();
    public final AtomicLong tenantL1Hit = new AtomicLong();
    public final AtomicLong routeL1Hit = new AtomicLong();
    public final AtomicLong credentialL1Hit = new AtomicLong();
    public final AtomicLong invalidKeyNegativeHit = new AtomicLong();
    public final AtomicLong redisSnapshotRead = new AtomicLong();
    public final AtomicLong redisReadFailure = new AtomicLong();
    public final AtomicLong singleflightJoin = new AtomicLong();
    public final AtomicLong invalidationReceived = new AtomicLong();
    public final AtomicLong invalidationIgnoredOld = new AtomicLong();
    public final AtomicLong failClosed = new AtomicLong();
    /** 中继观测指标不携带 API Key、模型文本、租户或 Redis Key 等高基数/敏感标签。 */
    public final AtomicLong relayEventsProduced = new AtomicLong();
    public final AtomicLong relayEventsPublishFailed = new AtomicLong();
    public final AtomicLong relayEventsRetry = new AtomicLong();
    public final AtomicLong relayEventsDropped = new AtomicLong();
    public final AtomicLong relayEventsPendingRetry = new AtomicLong();
    public final AtomicLong relayUsageReported = new AtomicLong();
    public final AtomicLong relayUsagePartial = new AtomicLong();
    public final AtomicLong relayUsageUnknown = new AtomicLong();
    public final AtomicLong relayPricingCalculated = new AtomicLong();
    public final AtomicLong relayPricingPartial = new AtomicLong();
    public final AtomicLong relayPricingUnavailable = new AtomicLong();
    public final AtomicLong upstreamRuntimeFailureEvent = new AtomicLong();
    public final AtomicLong relayAttemptRetry = new AtomicLong();
    public final AtomicLong relayRetryBlockedPossibleExecution = new AtomicLong();
    public final AtomicLong relayRetryBlockedClientCommitted = new AtomicLong();
    public final AtomicLong relaySchedulerNoCandidate = new AtomicLong();
}
