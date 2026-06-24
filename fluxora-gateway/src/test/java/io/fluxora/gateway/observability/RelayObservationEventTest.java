package io.fluxora.gateway.observability;

import io.fluxora.gateway.auth.AuthenticatedPrincipal;
import io.fluxora.gateway.relay.RelayUsage;
import io.vertx.core.json.JsonObject;
import java.util.Map;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Redis Stream 事件只允许经过字段白名单输出，防止运行时选路对象被意外整体序列化。 */
class RelayObservationEventTest {

    @Test
    void startedEventMustContainOnlySafeReferencesAndImmutablePriceStrings() {
        RelayPriceSnapshot price = RelayPriceSnapshot.fromRoute(new JsonObject()
                .put("tenantModelId", 31L)
                .put("tenantModelCode", "tenant-model")
                .put("currencyCode", "CNY")
                .put("priceVersion", 4)
                .put("inputPricePerMillion", "1.25")
                .put("outputPricePerMillion", "2.5")
                .put("cacheWritePricePerMillion", "3")
                .put("cacheReadPricePerMillion", "0.5")
                .put("baseUrl", "https://upstream.example/v1")
                .put("upstreamModelId", "hidden-model"));

        RelayObservationEvent event = RelayObservationEvent.started(
                "req-" + RelayRequestId.next(), new AuthenticatedPrincipal(7L, 8L, 9L),
                "OPENAI", "/v1/chat/completions", true, 41L, 42L, 43L, price);
        Map<String, String> fields = event.toStreamFields();

        assertEquals("RELAY_REQUEST_STARTED", fields.get("eventType"));
        assertEquals("8", fields.get("tenantId"));
        assertEquals("31", fields.get("tenantModelId"));
        assertEquals("1.25", fields.get("inputPricePerMillion"));
        assertEquals("UNKNOWN", fields.get("usageStatus"));
        assertEquals("NOT_APPLICABLE", fields.get("pricingStatus"));
        assertFalse(fields.containsKey("baseUrl"));
        assertFalse(fields.containsKey("upstreamModelId"));
        assertFalse(fields.containsKey("authorization"));
        assertFalse(fields.containsKey("requestBody"));
    }

    @Test
    void terminalEventMustOnlyCalculateWhenEveryPricedBucketIsKnown() {
        RelayPriceSnapshot price = new RelayPriceSnapshot(31L, "tenant-model", "CNY", 4,
                "1", "2", "3", "4");
        RelayObservationEvent complete = RelayObservationEvent.started("req-1", new AuthenticatedPrincipal(7L, 8L, 9L),
                        "ANTHROPIC", "/v1/messages", false, 41L, 42L, 43L, price)
                .finished(RelayUsage.from(10L, 20L, 30L, 40L), 200, 25L);
        RelayObservationEvent partial = RelayObservationEvent.started("req-2", new AuthenticatedPrincipal(7L, 8L, 9L),
                        "ANTHROPIC", "/v1/messages", false, 41L, 42L, 43L, price)
                .finished(RelayUsage.from(10L, 20L, null, 40L), 200, 25L);

        assertEquals("REPORTED", complete.toStreamFields().get("usageStatus"));
        assertEquals("CALCULATED", complete.toStreamFields().get("pricingStatus"));
        assertEquals("PARTIAL", partial.toStreamFields().get("usageStatus"));
        assertEquals("PARTIAL", partial.toStreamFields().get("pricingStatus"));
        assertNull(partial.toStreamFields().get("cacheWriteTokens"));
    }

    @Test
    void requestIdsMustBeOpaqueStandardUuidValues() {
        String first = RelayRequestId.next();
        String second = RelayRequestId.next();

        assertDoesNotThrow(() -> java.util.UUID.fromString(first));
        assertDoesNotThrow(() -> java.util.UUID.fromString(second));
        assertFalse(first.equals(second));
        assertTrue(first.length() == 36);
    }
}
