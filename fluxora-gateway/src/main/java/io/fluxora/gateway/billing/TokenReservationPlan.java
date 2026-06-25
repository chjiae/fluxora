package io.fluxora.gateway.billing;

import io.fluxora.gateway.GatewayFailure;
import io.fluxora.gateway.observability.RelayPriceSnapshot;
import io.vertx.core.json.JsonObject;
import java.math.BigDecimal;
import java.math.RoundingMode;

/**
 * Gateway 的保守预冻结计划。输入上限使用 UTF-8 请求体字节数：任何可编码输入 Token 数不超过字节数，
 * 因而这是可证明不低估的上界；超出 TenantModel 配置的请求直接拒绝而不发往上游。
 */
public record TokenReservationPlan(JsonObject normalizedInbound, long inputTokenCeiling, long outputTokenCeiling,
                                   long cacheWriteTokenCeiling, long cacheReadTokenCeiling, String reservationAmount) {
    private static final BigDecimal MILLION = BigDecimal.valueOf(1_000_000L);

    public static TokenReservationPlan build(JsonObject inbound, String protocol, JsonObject route, int utf8BodyBytes,
                                             RelayPriceSnapshot price) {
        long maxInput = positive(route.getLong("maxInputTokens"));
        long maxOutput = positive(route.getLong("maxOutputTokens"));
        long defaultOutput = positive(route.getLong("defaultOutputTokens"));
        if (defaultOutput > maxOutput || utf8BodyBytes < 0 || utf8BodyBytes > maxInput) {
            throw GatewayFailure.billingUnsupported();
        }
        Long maxTokens = exactPositiveLong(inbound.getValue("max_tokens"));
        Long maxCompletionTokens = exactPositiveLong(inbound.getValue("max_completion_tokens"));
        if (maxTokens != null && maxCompletionTokens != null && !maxTokens.equals(maxCompletionTokens)) {
            throw GatewayFailure.invalidRequest();
        }
        long output = maxTokens != null ? maxTokens : maxCompletionTokens != null ? maxCompletionTokens : defaultOutput;
        if (output > maxOutput) throw GatewayFailure.invalidRequest();
        JsonObject normalized = inbound.copy();
        if (maxTokens == null && maxCompletionTokens == null) normalized.put("max_tokens", output);
        long cacheWrite = ceilingForPricedCache(price.cacheWritePricePerMillion(), route.getLong("maxCacheWriteTokens"), utf8BodyBytes);
        long cacheRead = ceilingForPricedCache(price.cacheReadPricePerMillion(), route.getLong("maxCacheReadTokens"), utf8BodyBytes);
        return new TokenReservationPlan(normalized, utf8BodyBytes, output, cacheWrite, cacheRead,
                quote(utf8BodyBytes, output, cacheWrite, cacheRead, price));
    }

    private static long ceilingForPricedCache(String price, Long configuredMaximum, int bodyBytes) {
        if (price == null) return 0L;
        long maximum = positive(configuredMaximum);
        if (bodyBytes > maximum) throw GatewayFailure.billingUnsupported();
        return bodyBytes;
    }

    private static String quote(long input, long output, long cacheWrite, long cacheRead, RelayPriceSnapshot price) {
        try {
            BigDecimal total = BigDecimal.valueOf(input).multiply(new BigDecimal(price.inputPricePerMillion()))
                    .add(BigDecimal.valueOf(output).multiply(new BigDecimal(price.outputPricePerMillion())))
                    .add(BigDecimal.valueOf(cacheWrite).multiply(decimalOrZero(price.cacheWritePricePerMillion())))
                    .add(BigDecimal.valueOf(cacheRead).multiply(decimalOrZero(price.cacheReadPricePerMillion())));
            BigDecimal amount = total.divide(MILLION, 8, RoundingMode.CEILING).stripTrailingZeros();
            return amount.scale() < 0 ? amount.setScale(0).toPlainString() : amount.toPlainString();
        } catch (RuntimeException error) {
            throw GatewayFailure.billingUnsupported();
        }
    }

    private static BigDecimal decimalOrZero(String value) { return value == null ? BigDecimal.ZERO : new BigDecimal(value); }
    private static long positive(Long value) {
        if (value == null || value <= 0L) throw GatewayFailure.billingUnsupported();
        return value;
    }
    private static Long exactPositiveLong(Object value) {
        if (value == null) return null;
        if (!(value instanceof Number number) || number.longValue() <= 0L
                || Double.compare(number.doubleValue(), number.longValue()) != 0) throw GatewayFailure.invalidRequest();
        return number.longValue();
    }
}
