package io.fluxora.gateway.route;

import io.fluxora.gateway.GatewayFailure;
import io.fluxora.gateway.runtime.RuntimeL1Caches;
import io.vertx.core.Future;
import io.vertx.core.json.JsonObject;
import java.time.Instant;
import java.time.format.DateTimeParseException;

/** 使用单个 TENANT_MODEL_ROUTE 原子执行包完成模型、价格和目标选择，禁止跨 Scope 拼接配置。 */
public final class GatewayRouteResolver {
    private final RuntimeL1Caches caches;
    private final RouteTargetSelector selector;

    public GatewayRouteResolver(RuntimeL1Caches caches, RouteTargetSelector selector) {
        this.caches = caches;
        this.selector = selector;
    }

    public Future<RouteSelection> resolve(long tenantId, String inboundProtocol, String tenantModelCode,
                                           boolean streamingRequested) {
        if (tenantModelCode == null || tenantModelCode.isBlank() || tenantModelCode.length() > 128) {
            return Future.failedFuture(GatewayFailure.modelUnavailable());
        }
        String scopeKey = RouteScopeKey.of(tenantId, inboundProtocol, tenantModelCode);
        return caches.route(scopeKey).map(snapshot -> {
            JsonObject route = snapshot.payload();
            if (!modelUsable(route, tenantId, inboundProtocol, tenantModelCode, streamingRequested)) {
                throw GatewayFailure.modelUnavailable();
            }
            RouteSelection selection = selector.select(route.getJsonArray("targets"), inboundProtocol);
            if (selection == null) throw GatewayFailure.modelUnavailable();
            return selection;
        }).recover(error -> Future.failedFuture(error instanceof GatewayFailure
                ? error : GatewayFailure.runtimeUnavailable()));
    }

    private boolean modelUsable(JsonObject route, long tenantId, String protocol, String modelCode,
                                boolean streamingRequested) {
        return route.getLong("tenantId", -1L) == tenantId
                && protocol.equals(route.getString("inboundProtocol"))
                && modelCode.equals(route.getString("tenantModelCode"))
                && route.getBoolean("tenantModelEnabled", false)
                && "ENABLED".equals(route.getString("tenantModelStatus"))
                && route.getBoolean("routeEnabled", false)
                && "ENABLED".equals(route.getString("routeStatus"))
                && route.getBoolean("priceAvailable", false)
                && (!streamingRequested || route.getBoolean("supportsStreaming", false))
                && priceCurrent(route);
    }

    private boolean priceCurrent(JsonObject route) {
        try {
            String effective = route.getString("priceEffectiveAt");
            String expires = route.getString("priceExpiresAt");
            Instant now = Instant.now();
            return (effective == null || !Instant.parse(effective).isAfter(now))
                    && (expires == null || Instant.parse(expires).isAfter(now));
        } catch (DateTimeParseException error) {
            throw GatewayFailure.runtimeUnavailable();
        }
    }
}
