package io.fluxora.gateway.route;

import io.vertx.core.json.JsonObject;

/** 内部选中的目标；完整目标仅在 Gateway 请求作用域使用，绝不序列化给客户端。 */
public record RouteSelection(long routeTargetId, long providerChannelId, long providerChannelModelId,
                             String outboundProtocol, String upstreamModelId, JsonObject target, JsonObject routeSnapshot) {
    public RouteSelection(long routeTargetId, long providerChannelId, long providerChannelModelId,
                          String outboundProtocol, String upstreamModelId, JsonObject target) {
        this(routeTargetId, providerChannelId, providerChannelModelId, outboundProtocol, upstreamModelId, target, null);
    }

    /** 路由快照仅在本次请求内传递，用于固定价格；不会整体序列化到 Redis Stream。 */
    public RouteSelection withRouteSnapshot(JsonObject route) {
        return new RouteSelection(routeTargetId, providerChannelId, providerChannelModelId, outboundProtocol,
                upstreamModelId, target, route);
    }
}
