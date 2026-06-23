package io.fluxora.gateway;

import io.fluxora.gateway.auth.AuthenticatedPrincipal;
import io.vertx.core.Future;
import io.vertx.core.Vertx;
import io.vertx.core.buffer.Buffer;
import io.vertx.core.http.HttpMethod;
import io.vertx.core.http.HttpServer;
import io.vertx.core.http.HttpServerRequest;
import io.vertx.core.json.JsonObject;

/**
 * Gateway C 端入口：完成 API Key / 用户 / 租户鉴权和模型内部选路，随后明确停止于“不转发”。
 * 本轮没有 JDBC、上游 HTTP、SSE、协议转换、Token 统计或扣费代码。
 */
public final class GatewayHttpServer {
    private final Vertx vertx;
    private final GatewayRuntime runtime;
    private HttpServer server;

    public GatewayHttpServer(Vertx vertx, GatewayRuntime runtime) {
        this.vertx = vertx;
        this.runtime = runtime;
    }

    public Future<Integer> start(int port) {
        return vertx.createHttpServer().requestHandler(this::handle).listen(port)
                .onSuccess(started -> server = started).map(HttpServer::actualPort);
    }

    public Future<Void> close() {
        return server == null ? Future.succeededFuture() : server.close().compose(ignored -> runtime.close());
    }

    private void handle(HttpServerRequest request) {
        if (request.method() == HttpMethod.GET && "/health".equals(request.path())) {
            json(request, 200, new JsonObject().put("status", "UP"));
            return;
        }
        String protocol = protocolFor(request);
        if (protocol == null) {
            safeError(request, 404, "请求地址不存在");
            return;
        }
        String rawApiKey = extractApiKey(request);
        runtime.authenticator().authenticate(rawApiKey).compose(principal ->
                request.body().compose(body -> resolveAndStop(principal, protocol, body)))
                .onSuccess(ignored -> safeError(request, 501, "模型请求转发暂未开放"))
                .onFailure(error -> writeFailure(request, error));
    }

    private Future<Void> resolveAndStop(AuthenticatedPrincipal principal, String protocol, Buffer body) {
        JsonObject request;
        try {
            request = new JsonObject(body);
        } catch (RuntimeException error) {
            return Future.failedFuture(GatewayFailure.modelUnavailable());
        }
        String model = request.getString("model");
        boolean stream = request.getBoolean("stream", false);
        return runtime.routeResolver().resolve(principal.tenantId(), protocol, model, stream).mapEmpty();
    }

    private String protocolFor(HttpServerRequest request) {
        if (request.method() != HttpMethod.POST) return null;
        return switch (request.path()) {
            case "/v1/chat/completions" -> "OPENAI";
            case "/v1/messages" -> "ANTHROPIC";
            default -> null;
        };
    }

    private String extractApiKey(HttpServerRequest request) {
        String authorization = request.getHeader("Authorization");
        if (authorization != null && authorization.regionMatches(true, 0, "Bearer ", 0, 7)) {
            return authorization.substring(7);
        }
        return request.getHeader("x-api-key");
    }

    private void writeFailure(HttpServerRequest request, Throwable error) {
        GatewayFailure failure = error instanceof GatewayFailure known ? known : GatewayFailure.runtimeUnavailable();
        switch (failure.type()) {
            case INVALID_API_KEY -> safeError(request, 401, "API Key 无效或已失效");
            case ACCOUNT_UNAVAILABLE -> safeError(request, 403, "当前账号或租户不可用");
            case MODEL_UNAVAILABLE -> safeError(request, 503, "当前模型暂不可用，请稍后重试");
            case RUNTIME_UNAVAILABLE -> safeError(request, 503, "服务配置暂不可用，请稍后重试");
            case UNSUPPORTED -> safeError(request, 501, "当前服务暂不支持该请求");
        }
    }

    private void safeError(HttpServerRequest request, int status, String message) {
        json(request, status, new JsonObject().put("error", new JsonObject().put("message", message)));
    }

    private void json(HttpServerRequest request, int status, JsonObject body) {
        request.response().setStatusCode(status).putHeader("content-type", "application/json").end(body.encode());
    }
}
