package io.fluxora.gateway.route;

/** 内部选中的目标；只在 Gateway 进程内存在，绝不序列化给客户端。 */
public record RouteSelection(long routeTargetId, long providerChannelId, long providerChannelModelId,
                             String outboundProtocol, String upstreamModelId) {
}
