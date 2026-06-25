package io.fluxora.gateway.relay.runtime;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

/** 运行时故障事件的 Redis Stream 白名单字段，严禁放入密钥、BaseUrl、正文或异常文本。 */
public record RuntimeFailureEvent(String eventId, long tenantId, String requestId, String attemptId, int attemptNo,
                                  Instant occurredAt, RuntimeIncident incident) {
    private static final String EVENT_TYPE = "UPSTREAM_RUNTIME_FAILURE_DETECTED";

    public static RuntimeFailureEvent from(long tenantId, RuntimeIncident incident) {
        return new RuntimeFailureEvent(UUID.randomUUID().toString(), tenantId, incident.requestId(),
                incident.attemptId(), incident.attemptNo(), incident.occurredAt(), incident);
    }

    public Map<String, String> toStreamFields() {
        Map<String, String> fields = new LinkedHashMap<>();
        put(fields, "eventId", eventId);
        put(fields, "eventType", EVENT_TYPE);
        put(fields, "schemaVersion", "1");
        put(fields, "tenantId", tenantId);
        put(fields, "requestId", requestId);
        put(fields, "attemptId", attemptId);
        put(fields, "attemptNo", attemptNo);
        put(fields, "occurredAt", occurredAt);
        put(fields, "credentialId", incident.plan().providerCredentialId());
        put(fields, "providerChannelCredentialId", incident.plan().providerChannelCredentialId());
        put(fields, "providerChannelId", incident.plan().providerChannelId());
        put(fields, "providerChannelModelId", incident.plan().providerChannelModelId());
        put(fields, "routeTargetId", incident.plan().routeTargetId());
        put(fields, "billingAccountGroup", incident.plan().billingAccountGroup());
        put(fields, "quotaScope", incident.plan().quotaScope());
        put(fields, "failureKind", incident.classification().kind().name());
        put(fields, "failureScope", incident.classification().scope().name());
        put(fields, "httpStatus", incident.httpStatus());
        put(fields, "retryAfterMs", incident.classification().cooldownAdvice().retryAfterMs());
        put(fields, "executionCertainty", incident.classification().executionCertainty().name());
        return fields;
    }

    private static void put(Map<String, String> fields, String key, Object value) {
        if (value != null) fields.put(key, value instanceof Instant instant ? instant.toString() : value.toString());
    }
}
