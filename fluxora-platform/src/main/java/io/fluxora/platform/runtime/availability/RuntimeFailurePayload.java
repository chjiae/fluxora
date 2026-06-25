package io.fluxora.platform.runtime.availability;

import java.time.Instant;
import java.util.Map;

/** Gateway 运行时故障事件的脱敏载荷；字段白名单解析，忽略任何未知字段。 */
public record RuntimeFailurePayload(String eventId, long tenantId, String requestId, String attemptId, int attemptNo,
                                    Instant occurredAt, Long credentialId, Long providerChannelCredentialId,
                                    Long providerChannelId, Long providerChannelModelId, Long routeTargetId,
                                    String billingAccountGroup, String quotaScope, String failureKind,
                                    String failureScope, Integer httpStatus, Long retryAfterMs,
                                    String executionCertainty) {
    public static RuntimeFailurePayload from(Map<String, String> fields) {
        return new RuntimeFailurePayload(required(fields, "eventId"), number(fields, "tenantId"),
                required(fields, "requestId"), required(fields, "attemptId"),
                Math.toIntExact(number(fields, "attemptNo")), instant(fields, "occurredAt"),
                optionalLong(fields, "credentialId"), optionalLong(fields, "providerChannelCredentialId"),
                optionalLong(fields, "providerChannelId"), optionalLong(fields, "providerChannelModelId"),
                optionalLong(fields, "routeTargetId"), blankToNull(fields.get("billingAccountGroup")),
                blankToNull(fields.get("quotaScope")), required(fields, "failureKind"),
                required(fields, "failureScope"), optionalInt(fields, "httpStatus"),
                optionalLong(fields, "retryAfterMs"), required(fields, "executionCertainty"));
    }

    private static String required(Map<String, String> fields, String key) {
        String value = fields.get(key);
        if (value == null || value.isBlank()) throw new IllegalArgumentException("运行时故障事件字段不完整");
        return value;
    }

    private static long number(Map<String, String> fields, String key) { return Long.parseLong(required(fields, key)); }
    private static Instant instant(Map<String, String> fields, String key) { return Instant.parse(required(fields, key)); }
    private static Long optionalLong(Map<String, String> fields, String key) {
        String value = fields.get(key);
        return value == null || value.isBlank() ? null : Long.parseLong(value);
    }
    private static Integer optionalInt(Map<String, String> fields, String key) {
        String value = fields.get(key);
        return value == null || value.isBlank() ? null : Integer.valueOf(value);
    }
    private static String blankToNull(String value) { return value == null || value.isBlank() ? null : value; }
}
