package io.fluxora.gateway;

import java.util.concurrent.atomic.AtomicLong;

/** 无敏感标签的轻量内部计数器；不记录 API Key、用户、模型文本或完整 Redis Key。 */
public final class GatewayMetrics {
    public final AtomicLong apiKeyL1Hit = new AtomicLong();
    public final AtomicLong userL1Hit = new AtomicLong();
    public final AtomicLong tenantL1Hit = new AtomicLong();
    public final AtomicLong routeL1Hit = new AtomicLong();
    public final AtomicLong invalidKeyNegativeHit = new AtomicLong();
    public final AtomicLong redisSnapshotRead = new AtomicLong();
    public final AtomicLong redisReadFailure = new AtomicLong();
    public final AtomicLong singleflightJoin = new AtomicLong();
    public final AtomicLong invalidationReceived = new AtomicLong();
    public final AtomicLong invalidationIgnoredOld = new AtomicLong();
    public final AtomicLong failClosed = new AtomicLong();
}
