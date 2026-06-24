package io.fluxora.gateway.route;

import io.vertx.core.json.JsonObject;

/** 内部选中的目标；完整目标仅在 Gateway 请求作用域使用，绝不序列化给客户端。 */
public record RouteSelection(long routeTargetId, long providerChannelId, long providerChannelModelId,
                             String outboundProtocol, String upstreamModelId, JsonObject target) {
}
