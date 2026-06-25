package io.fluxora.platform.observability;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.Map;

/** Redis Stream 的安全字段契约；解析时只读取 Gateway 白名单字段，任何额外字段一律忽略。 */
public record RelayEventPayload(String eventId, String eventType, String requestId, Instant occurredAt, long tenantId,
                         long userId, long apiKeyId, String inboundProtocol, String outboundProtocol, String endpoint,
                         long tenantModelId, String tenantModelCode, long routeTargetId, long providerChannelId,
                         long providerChannelModelId, boolean streaming, Instant requestStartedAt,
                         Instant requestFinishedAt, Long durationMs, String requestStatus, String errorCategory,
                         Integer safeHttpStatus, String usageStatus, Long inputTokens, Long outputTokens,
                         Long cacheWriteTokens, Long cacheReadTokens, String currencyCode, int priceVersion,
                         BigDecimal inputPricePerMillion, BigDecimal outputPricePerMillion,
                         BigDecimal cacheWritePricePerMillion, BigDecimal cacheReadPricePerMillion,
                         String pricingStatus, String upstreamDispatchState) {
    public static RelayEventPayload from(Map<String, String> fields) {
        return new RelayEventPayload(required(fields, "eventId"), required(fields, "eventType"), required(fields, "requestId"),
                instant(fields, "occurredAt"), number(fields, "tenantId"), number(fields, "userId"), number(fields, "apiKeyId"),
                required(fields, "inboundProtocol"), required(fields, "outboundProtocol"), required(fields, "endpoint"),
                number(fields, "tenantModelId"), required(fields, "tenantModelCode"), number(fields, "routeTargetId"),
                number(fields, "providerChannelId"), number(fields, "providerChannelModelId"), Boolean.parseBoolean(required(fields, "stream")),
                instant(fields, "requestStartedAt"), nullableInstant(fields, "requestFinishedAt"), nullableLong(fields, "durationMs"),
                required(fields, "requestStatus"), fields.get("errorCategory"), nullableInt(fields, "safeHttpStatus"),
                required(fields, "usageStatus"), nullableLong(fields, "inputTokens"), nullableLong(fields, "outputTokens"),
                nullableLong(fields, "cacheWriteTokens"), nullableLong(fields, "cacheReadTokens"), required(fields, "currencyCode"),
                Math.toIntExact(number(fields, "priceVersion")), decimal(fields, "inputPricePerMillion"), decimal(fields, "outputPricePerMillion"),
                nullableDecimal(fields, "cacheWritePricePerMillion"), nullableDecimal(fields, "cacheReadPricePerMillion"),
                required(fields, "pricingStatus"), optional(fields, "upstreamDispatchState", "UNKNOWN"));
    }
    private static String required(Map<String,String> f,String k){String v=f.get(k);if(v==null||v.isBlank())throw new IllegalArgumentException("中继事件字段不完整");return v;}
    private static String optional(Map<String,String> f,String k,String d){String v=f.get(k);return v==null||v.isBlank()?d:v;}
    private static long number(Map<String,String> f,String k){return Long.parseLong(required(f,k));}
    private static Long nullableLong(Map<String,String> f,String k){String v=f.get(k);return v==null||v.isBlank()?null:Long.parseLong(v);}
    private static Integer nullableInt(Map<String,String> f,String k){String v=f.get(k);return v==null||v.isBlank()?null:Integer.valueOf(v);}
    private static Instant instant(Map<String,String> f,String k){return Instant.parse(required(f,k));}
    private static Instant nullableInstant(Map<String,String> f,String k){String v=f.get(k);return v==null||v.isBlank()?null:Instant.parse(v);}
    private static BigDecimal decimal(Map<String,String> f,String k){return new BigDecimal(required(f,k));}
    private static BigDecimal nullableDecimal(Map<String,String> f,String k){String v=f.get(k);return v==null||v.isBlank()?null:new BigDecimal(v);}
}
