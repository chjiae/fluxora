package io.fluxora.gateway.observability;

import io.vertx.core.json.JsonObject;

/**
 * 请求开始时固化的价格版本。Gateway 只从已验证的运行时路由快照读取，
 * 绝不把目标中的 baseUrl、上游模型或凭证引用放入该对象或 Stream 事件。
 */
public record RelayPriceSnapshot(long tenantModelId, String tenantModelCode, String currencyCode, int priceVersion,
                                 String inputPricePerMillion, String outputPricePerMillion,
                                 String cacheWritePricePerMillion, String cacheReadPricePerMillion) {

    public static RelayPriceSnapshot fromRoute(JsonObject route) {
        return new RelayPriceSnapshot(route.getLong("tenantModelId"), route.getString("tenantModelCode"),
                route.getString("currencyCode"), route.getInteger("priceVersion", 0),
                decimal(route, "inputPricePerMillion"), decimal(route, "outputPricePerMillion"),
                decimal(route, "cacheWritePricePerMillion"), decimal(route, "cacheReadPricePerMillion"));
    }

    /**
     * 本轮上游实际是同协议中继；若某缓存桶配置了价格但未得到 usage，
     * Platform 只能标记 PARTIAL，而不能把缺失值按零元计算。
     */
    public boolean requiresCacheWriteUsage() {
        return cacheWritePricePerMillion != null;
    }

    public boolean requiresCacheReadUsage() {
        return cacheReadPricePerMillion != null;
    }

    private static String decimal(JsonObject route, String field) {
        Object value = route.getValue(field);
        return value == null ? null : value.toString();
    }
}
