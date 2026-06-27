package io.fluxora.gateway.route;

import io.fluxora.gateway.GatewayFailure;
import io.fluxora.gateway.runtime.RuntimeL1Caches;
import io.fluxora.common.runtime.RouteExecutionEligibility;
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
        return resolveRouteSnapshot(tenantId, inboundProtocol, tenantModelCode, streamingRequested)
                .map(route -> selectFromRoute(route, inboundProtocol));
    }

    /** 在已校验的路由执行包内选择目标，无可用目标时抛模型不可用，统一进入失败处理。 */
    private RouteSelection selectFromRoute(JsonObject route, String inboundProtocol) {
        RouteSelection selection = selector.select(route.getJsonArray("targets"), inboundProtocol);
        // 路由执行包可用但选择器未能挑出任何目标，说明全部目标不可调用，按模型不可用处理
        if (selection == null) throw GatewayFailure.modelUnavailable();
        return selection.withRouteSnapshot(route);
    }

    /** 返回已验证的路由执行包，供 Attempt 调度器在同一请求内多次选择不同目标/凭证。 */
    public Future<JsonObject> resolveRouteSnapshot(long tenantId, String inboundProtocol, String tenantModelCode,
                                                   boolean streamingRequested) {
        // 模型码缺失或超长直接判定模型不可用，避免构造无效 Scope Key 命中缓存
        if (tenantModelCode == null || tenantModelCode.isBlank() || tenantModelCode.length() > 128) {
            return Future.failedFuture(GatewayFailure.modelUnavailable());
        }
        String scopeKey = RouteScopeKey.of(tenantId, inboundProtocol, tenantModelCode);
        return caches.route(scopeKey)
                .map(snapshot -> validateRouteSnapshot(snapshot.payload(), tenantId, inboundProtocol,
                        tenantModelCode, streamingRequested))
                // 仅 GatewayFailure 是业务可对外语义，其余异常统一收敛为运行时不可用，避免泄露内部细节
                .recover(error -> Future.failedFuture(error instanceof GatewayFailure
                        ? error : GatewayFailure.runtimeUnavailable()));
    }

    /** 校验路由执行包：租户、协议、模型码、启用状态、价格有效期与流式能力任一不满足即判定模型不可用。 */
    private JsonObject validateRouteSnapshot(JsonObject route, long tenantId, String inboundProtocol,
                                             String tenantModelCode, boolean streamingRequested) {
        if (!modelUsable(route, tenantId, inboundProtocol, tenantModelCode, streamingRequested)) {
            throw GatewayFailure.modelUnavailable();
        }
        return route;
    }

    private boolean modelUsable(JsonObject route, long tenantId, String protocol, String modelCode,
                                boolean streamingRequested) {
        Instant now = Instant.now();
        return route.getLong("tenantId", -1L) == tenantId
                && protocol.equals(route.getString("inboundProtocol"))
                && modelCode.equals(route.getString("tenantModelCode"))
                && "ENABLED".equals(route.getString("tenantModelStatus"))
                && "ENABLED".equals(route.getString("routeStatus"))
                && RouteExecutionEligibility.baseRouteCallable(route.getBoolean("tenantModelEnabled", false),
                        route.getBoolean("routeEnabled", false), route.getBoolean("priceAvailable", false),
                        instant(route.getString("priceEffectiveAt")), instant(route.getString("priceExpiresAt")), now)
                && (!streamingRequested || route.getBoolean("supportsStreaming", false))
                ;
    }

    private Instant instant(String value) {
        if (value == null) return null;
        try {
            return Instant.parse(value);
        } catch (DateTimeParseException error) {
            throw GatewayFailure.runtimeUnavailable();
        }
    }
}
