package io.fluxora.gateway.observability;

import io.fluxora.gateway.GatewayMetrics;
import io.fluxora.gateway.relay.RelayUsageStatus;
import io.vertx.redis.client.Redis;
import java.util.ArrayDeque;
import java.util.Deque;

/**
 * Gateway 侧非阻塞事件投递器。Redis 可用时为 At-Least-Once；不可用时仅内存有界重试，
 * 因而进程崩溃仍可能丢失待重试事件。该边界会由指标与文档明确告知，不能伪称 Exactly Once。
 */
public final class RelayEventPublisher {
    private final RelayEventStreamClient client;
    private final GatewayMetrics metrics;
    private final int maxQueueSize;
    private final int maxAttempts;
    private final Deque<PendingEvent> pending = new ArrayDeque<>();

    RelayEventPublisher(RelayEventStreamClient client, GatewayMetrics metrics, int maxQueueSize, int maxAttempts) {
        this.client = client;
        this.metrics = metrics;
        this.maxQueueSize = maxQueueSize;
        this.maxAttempts = maxAttempts;
    }

    /** Gateway 组合根使用的 Redis 构造入口，避免将 Vert.x Redis 类型泄露给中继业务层。 */
    public static RelayEventPublisher forRedis(Redis redis, GatewayMetrics metrics, String streamKey,
                                               int maxLength, int maxQueueSize, int maxAttempts) {
        return new RelayEventPublisher(new RedisRelayEventStreamClient(redis, streamKey, maxLength), metrics,
                maxQueueSize, maxAttempts);
    }

    /** 立即发起异步 XADD；失败只进入观测重试队列，绝不向调用者抛出 Redis 异常。 */
    public void publish(RelayObservationEvent event) {
        client.append(event.toStreamFields()).onSuccess(ignored -> {
                    metrics.relayEventsProduced.incrementAndGet();
                    recordObservationMetrics(event);
                })
                .onFailure(ignored -> {
                    metrics.relayEventsPublishFailed.incrementAndGet();
                    enqueue(event);
                });
    }

    /**
     * 由 Gateway 的低频 Vert.x 定时器调用，每次只重试队首一个事件，
     * 防止 Redis 恢复瞬间在 Event Loop 上突发大量命令。
     */
    public void retryPending() {
        PendingEvent next = pending.peekFirst();
        if (next == null) {
            return;
        }
        metrics.relayEventsRetry.incrementAndGet();
        client.append(next.event().toStreamFields()).onSuccess(ignored -> {
            pending.removeFirstOccurrence(next);
            metrics.relayEventsProduced.incrementAndGet();
            recordObservationMetrics(next.event());
            refreshPendingMetric();
        }).onFailure(ignored -> {
            metrics.relayEventsPublishFailed.incrementAndGet();
            next.incrementAttempts();
            if (next.attempts() >= maxAttempts) {
                pending.removeFirstOccurrence(next);
                metrics.relayEventsDropped.incrementAndGet();
            }
            refreshPendingMetric();
        });
    }

    int pendingCount() {
        return pending.size();
    }

    private void enqueue(RelayObservationEvent event) {
        if (pending.size() >= maxQueueSize) {
            metrics.relayEventsDropped.incrementAndGet();
            return;
        }
        pending.addLast(new PendingEvent(event));
        refreshPendingMetric();
    }

    private void refreshPendingMetric() {
        metrics.relayEventsPendingRetry.set(pending.size());
    }

    private void recordObservationMetrics(RelayObservationEvent event) {
        if (event.eventType() == RelayEventType.RELAY_REQUEST_STARTED) {
            return;
        }
        String usageStatus = event.toStreamFields().get("usageStatus");
        if (RelayUsageStatus.REPORTED.name().equals(usageStatus)) metrics.relayUsageReported.incrementAndGet();
        else if (RelayUsageStatus.PARTIAL.name().equals(usageStatus)) metrics.relayUsagePartial.incrementAndGet();
        else if (RelayUsageStatus.UNKNOWN.name().equals(usageStatus)) metrics.relayUsageUnknown.incrementAndGet();
        String pricingStatus = event.toStreamFields().get("pricingStatus");
        if ("CALCULATED".equals(pricingStatus)) metrics.relayPricingCalculated.incrementAndGet();
        else if ("PARTIAL".equals(pricingStatus)) metrics.relayPricingPartial.incrementAndGet();
        else if ("UNAVAILABLE".equals(pricingStatus)) metrics.relayPricingUnavailable.incrementAndGet();
    }

    private static final class PendingEvent {
        private final RelayObservationEvent event;
        private int attempts;

        private PendingEvent(RelayObservationEvent event) {
            this.event = event;
        }

        private RelayObservationEvent event() { return event; }
        private int attempts() { return attempts; }
        private void incrementAttempts() { attempts++; }
    }
}
