package io.fluxora.gateway.observability;

import io.fluxora.gateway.auth.AuthenticatedPrincipal;
import io.fluxora.gateway.relay.RelayUsage;
import io.fluxora.gateway.relay.RelayUsageStatus;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

/**
 * Redis Stream 的最小安全事件。序列化采用显式字段白名单，禁止直接序列化路由、
 * HTTP 头、请求正文、响应正文或异常对象，避免凭证与提示词进入可观测数据面。
 */
public record RelayObservationEvent(String eventId, RelayEventType eventType, String requestId, Instant occurredAt,
                                    long tenantId, long userId, long apiKeyId, String inboundProtocol,
                                    String outboundProtocol, String endpoint, long tenantModelId,
                                    String tenantModelCode, long routeTargetId, long providerChannelId,
                                    long providerChannelModelId, boolean streaming, Instant requestStartedAt,
                                    Instant requestFinishedAt, Long durationMs, String requestStatus,
                                    String errorCategory, Integer safeHttpStatus, RelayUsage usage,
                                    RelayPriceSnapshot priceSnapshot) {
    private static final String SCHEMA_VERSION = "1";

    public static RelayObservationEvent started(String requestId, AuthenticatedPrincipal principal,
                                                String protocol, String endpoint, boolean streaming,
                                                long routeTargetId, long providerChannelId,
                                                long providerChannelModelId, RelayPriceSnapshot priceSnapshot) {
        Instant now = Instant.now();
        return new RelayObservationEvent(UUID.randomUUID().toString(), RelayEventType.RELAY_REQUEST_STARTED, requestId,
                now, principal.tenantId(), principal.userId(), principal.apiKeyId(), protocol, protocol, endpoint,
                priceSnapshot.tenantModelId(), priceSnapshot.tenantModelCode(), routeTargetId, providerChannelId,
                providerChannelModelId, streaming, now, null, null, "STARTED", null, null,
                RelayUsage.unknown(), priceSnapshot);
    }

    public RelayObservationEvent finished(RelayUsage terminalUsage, int statusCode, long elapsedMs) {
        return terminal(RelayEventType.RELAY_REQUEST_FINISHED, "SUCCESS", null, statusCode, terminalUsage, elapsedMs);
    }

    public RelayObservationEvent failed(String safeErrorCategory, Integer statusCode, RelayUsage terminalUsage,
                                        long elapsedMs) {
        return terminal(RelayEventType.RELAY_REQUEST_FAILED, "FAILED", safeErrorCategory, statusCode, terminalUsage, elapsedMs);
    }

    public RelayObservationEvent cancelled(RelayUsage terminalUsage, long elapsedMs) {
        return terminal(RelayEventType.RELAY_REQUEST_CANCELLED, "CANCELLED", "CLIENT_CANCELLED", null, terminalUsage, elapsedMs);
    }

    private RelayObservationEvent terminal(RelayEventType type, String status, String error, Integer httpStatus,
                                           RelayUsage terminalUsage, long elapsedMs) {
        return new RelayObservationEvent(UUID.randomUUID().toString(), type, requestId, Instant.now(), tenantId, userId,
                apiKeyId, inboundProtocol, outboundProtocol, endpoint, tenantModelId, tenantModelCode, routeTargetId,
                providerChannelId, providerChannelModelId, streaming, requestStartedAt, Instant.now(), elapsedMs,
                status, error, httpStatus, terminalUsage == null ? RelayUsage.unknown() : terminalUsage, priceSnapshot);
    }

    public Map<String, String> toStreamFields() {
        Map<String, String> fields = new LinkedHashMap<>();
        put(fields, "eventId", eventId); put(fields, "eventType", eventType.name()); put(fields, "schemaVersion", SCHEMA_VERSION);
        put(fields, "requestId", requestId); put(fields, "occurredAt", occurredAt); put(fields, "tenantId", tenantId);
        put(fields, "userId", userId); put(fields, "apiKeyId", apiKeyId); put(fields, "inboundProtocol", inboundProtocol);
        put(fields, "outboundProtocol", outboundProtocol); put(fields, "endpoint", endpoint); put(fields, "tenantModelId", tenantModelId);
        put(fields, "tenantModelCode", tenantModelCode); put(fields, "routeTargetId", routeTargetId);
        put(fields, "providerChannelId", providerChannelId); put(fields, "providerChannelModelId", providerChannelModelId);
        put(fields, "stream", streaming); put(fields, "requestStartedAt", requestStartedAt); put(fields, "requestFinishedAt", requestFinishedAt);
        put(fields, "durationMs", durationMs); put(fields, "requestStatus", requestStatus); put(fields, "errorCategory", errorCategory);
        put(fields, "safeHttpStatus", safeHttpStatus); put(fields, "usageStatus", effectiveUsageStatus().name());
        put(fields, "pricingStatus", pricingStatus()); put(fields, "inputTokens", usage.inputTokens());
        put(fields, "outputTokens", usage.outputTokens()); put(fields, "cacheWriteTokens", usage.cacheWriteTokens());
        put(fields, "cacheReadTokens", usage.cacheReadTokens()); put(fields, "currencyCode", priceSnapshot.currencyCode());
        put(fields, "priceVersion", priceSnapshot.priceVersion()); put(fields, "inputPricePerMillion", priceSnapshot.inputPricePerMillion());
        put(fields, "outputPricePerMillion", priceSnapshot.outputPricePerMillion()); put(fields, "cacheWritePricePerMillion", priceSnapshot.cacheWritePricePerMillion());
        put(fields, "cacheReadPricePerMillion", priceSnapshot.cacheReadPricePerMillion());
        return fields;
    }

    private RelayUsageStatus effectiveUsageStatus() {
        if (usage.status() == RelayUsageStatus.UNKNOWN || usage.status() == RelayUsageStatus.NOT_APPLICABLE) {
            return usage.status();
        }
        if (usage.inputTokens() == null || usage.outputTokens() == null
                || priceSnapshot.requiresCacheWriteUsage() && usage.cacheWriteTokens() == null
                || priceSnapshot.requiresCacheReadUsage() && usage.cacheReadTokens() == null) {
            return RelayUsageStatus.PARTIAL;
        }
        return RelayUsageStatus.REPORTED;
    }

    private String pricingStatus() {
        if (eventType == RelayEventType.RELAY_REQUEST_STARTED || usage.status() == RelayUsageStatus.NOT_APPLICABLE) {
            return "NOT_APPLICABLE";
        }
        return switch (effectiveUsageStatus()) {
            case REPORTED -> "CALCULATED";
            case PARTIAL -> "PARTIAL";
            case UNKNOWN -> "UNAVAILABLE";
            case NOT_APPLICABLE -> "NOT_APPLICABLE";
        };
    }

    private static void put(Map<String, String> fields, String key, Object value) {
        if (value != null) {
            fields.put(key, value instanceof Instant instant ? instant.toString() : value.toString());
        }
    }
}
