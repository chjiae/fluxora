package io.fluxora.gateway.relay;

import io.fluxora.gateway.GatewayFailure;
import io.vertx.core.json.JsonObject;

/**
 * 中继请求的最小安全归一化。
 *
 * <p>本类只负责把缺省输出 Token 上限写成确定值，避免上游实际执行范围超出路由快照约束。
 * 它不计算金额、不访问 Platform。</p>
 */
public final class RelayRequestNormalizer {
    private RelayRequestNormalizer() {
    }

    public static JsonObject normalize(JsonObject inbound, JsonObject routeSnapshot) {
        long defaultOutput = positive(routeSnapshot.getLong("defaultOutputTokens"));
        Long maxTokens = exactPositiveLong(inbound.getValue("max_tokens"));
        Long maxCompletionTokens = exactPositiveLong(inbound.getValue("max_completion_tokens"));
        if (maxTokens != null && maxCompletionTokens != null && !maxTokens.equals(maxCompletionTokens)) {
            throw GatewayFailure.invalidRequest();
        }
        long output = maxTokens != null ? maxTokens : maxCompletionTokens != null ? maxCompletionTokens : defaultOutput;
        JsonObject normalized = inbound.copy();
        if (maxTokens == null && maxCompletionTokens == null) {
            normalized.put("max_tokens", output);
        }
        return normalized;
    }

    private static long positive(Long value) {
        if (value == null || value <= 0L) {
            throw GatewayFailure.invalidRequest();
        }
        return value;
    }

    private static Long exactPositiveLong(Object value) {
        if (value == null) {
            return null;
        }
        if (!(value instanceof Number number) || number.longValue() <= 0L
                || Double.compare(number.doubleValue(), number.longValue()) != 0) {
            throw GatewayFailure.invalidRequest();
        }
        return number.longValue();
    }
}
