package io.fluxora.gateway.observability;

import io.fluxora.gateway.GatewayMetrics;
import io.fluxora.gateway.auth.AuthenticatedPrincipal;
import io.vertx.core.Future;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

/** Redis 临时故障不应影响客户响应；可靠性边界由有界队列与指标明确表达。 */
class RelayEventPublisherTest {

    @Test
    void failedPublishMustQueueAndRetryWithoutPropagatingFailureToRelay() {
        AtomicInteger attempts = new AtomicInteger();
        RelayEventStreamClient client = fields -> attempts.incrementAndGet() == 1
                ? Future.failedFuture("redis unavailable") : Future.succeededFuture();
        GatewayMetrics metrics = new GatewayMetrics();
        RelayEventPublisher publisher = new RelayEventPublisher(client, metrics, 4, 3);

        publisher.publish(event("req-retry"));
        assertEquals(1, publisher.pendingCount());
        assertEquals(1L, metrics.relayEventsPublishFailed.get());

        publisher.retryPending();
        assertEquals(0, publisher.pendingCount());
        assertEquals(1L, metrics.relayEventsRetry.get());
        assertEquals(1L, metrics.relayEventsProduced.get());
    }

    @Test
    void fullRetryQueueMustDropOnlyObservationEventAndExposeMetric() {
        RelayEventStreamClient client = fields -> Future.failedFuture("redis unavailable");
        GatewayMetrics metrics = new GatewayMetrics();
        RelayEventPublisher publisher = new RelayEventPublisher(client, metrics, 1, 1);

        publisher.publish(event("req-one"));
        publisher.publish(event("req-two"));

        assertEquals(1, publisher.pendingCount());
        assertEquals(1L, metrics.relayEventsDropped.get());
        assertEquals(1L, metrics.relayEventsPendingRetry.get());
    }

    private RelayObservationEvent event(String requestId) {
        return RelayObservationEvent.started(requestId, new AuthenticatedPrincipal(1L, 2L, 3L),
                "OPENAI", "/v1/chat/completions", false, 4L, 5L, 6L,
                new RelayPriceSnapshot(7L, "model", "CNY", 1, "1", "1", null, null));
    }
}
