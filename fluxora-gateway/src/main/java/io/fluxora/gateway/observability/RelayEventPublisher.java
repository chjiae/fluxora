package io.fluxora.gateway.observability;

import io.fluxora.gateway.GatewayMetrics;
import io.fluxora.gateway.relay.RelayUsageStatus;
import io.fluxora.gateway.relay.runtime.RuntimeFailureEvent;
import io.vertx.redis.client.Redis;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.Map;

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
        publishFields(event.toStreamFields(), event);
    }

    /** 运行时故障事件与请求日志事件共用同一可靠投递边界，但不会进入请求用量统计。 */
    public void publishRuntimeFailure(RuntimeFailureEvent event) {
        metrics.upstreamRuntimeFailureEvent.incrementAndGet();
        publishFields(event.toStreamFields(), null);
    }

    private void publishFields(Map<String, String> fields, RelayObservationEvent observation) {
        client.append(fields)
                // 投递成功：计入生产计数；请求日志类事件需要进一步记录用量与计价观测指标
                .onSuccess(ignored -> {
                    metrics.relayEventsProduced.incrementAndGet();
                    if (observation != null) recordObservationMetrics(observation);
                })
                // 投递失败：绝不向调用者抛出 Redis 异常，仅计入失败计数并进入内存有界重试队列
                .onFailure(ignored -> {
                    metrics.relayEventsPublishFailed.incrementAndGet();
                    enqueue(fields, observation);
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
        client.append(next.fields())
                // 重试成功：从队列移除并计入生产计数，请求日志类事件补充观测指标
                .onSuccess(ignored -> {
                    pending.removeFirstOccurrence(next);
                    metrics.relayEventsProduced.incrementAndGet();
                    if (next.observation() != null) recordObservationMetrics(next.observation());
                    refreshPendingMetric();
                })
                // 重试失败：累加重试次数，达到上限则丢弃并计入丢弃计数，避免单条毒消息长期占用队列
                .onFailure(ignored -> {
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

    private void enqueue(Map<String, String> fields, RelayObservationEvent observation) {
        if (pending.size() >= maxQueueSize) {
            metrics.relayEventsDropped.incrementAndGet();
            return;
        }
        pending.addLast(new PendingEvent(fields, observation));
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
        private final Map<String, String> fields;
        private final RelayObservationEvent observation;
        private int attempts;

        private PendingEvent(Map<String, String> fields, RelayObservationEvent observation) {
            this.fields = Map.copyOf(fields);
            this.observation = observation;
        }

        private Map<String, String> fields() { return fields; }
        private RelayObservationEvent observation() { return observation; }
        private int attempts() { return attempts; }
        private void incrementAttempts() { attempts++; }
    }
}
