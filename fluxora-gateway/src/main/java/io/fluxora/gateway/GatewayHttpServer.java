package io.fluxora.gateway;

import io.fluxora.gateway.relay.RelayService;
import io.fluxora.gateway.transport.UpstreamHttpClient;
import io.vertx.core.Future;
import io.vertx.core.Vertx;
import io.vertx.core.buffer.Buffer;
import io.vertx.core.http.HttpMethod;
import io.vertx.core.http.HttpServer;
import io.vertx.core.http.HttpServerRequest;
import io.vertx.core.json.JsonObject;

/**
 * Gateway C 端入口：完成协议识别、请求体上限、鉴权、选路和同协议原生中继。
 * 不包含跨协议转换、计费、重试或熔断等后续能力。
 */
public final class GatewayHttpServer {
    private final Vertx vertx;
    private final GatewayRuntime runtime;
    private final RelayService relayService;
    private final GatewayRuntimeConfig runtimeConfig;
    private HttpServer server;

    public GatewayHttpServer(Vertx vertx, GatewayRuntime runtime) {
        this.vertx = vertx;
        this.runtime = runtime;
        this.runtimeConfig = GatewayRuntimeConfig.fromEnvironment();
        this.relayService = new RelayService(runtime, new UpstreamHttpClient(vertx), runtimeConfig.profile());
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
        if (request.method() == HttpMethod.GET && "/v1/models".equals(request.path())) {
            runtime.authenticator().authenticate(apiKey(request))
                    .compose(principal -> runtime.modelCatalog().listOpenAiModels(principal.tenantId()))
                    .onSuccess(body -> json(request, 200, body))
                    .onFailure(error -> writeFailure(request, "OPENAI", error));
            return;
        }
        String protocol = protocolFor(request);
        if (protocol == null) {
            safeError(request, 404, "请求地址不存在");
            return;
        }
        if (contentLengthExceedsLimit(request)) {
            writeFailure(request, protocol, GatewayFailure.invalidRequest());
            return;
        }
        request.body().compose(body -> body.length() > runtimeConfig.maxRequestBodyBytes()
                        ? Future.failedFuture(GatewayFailure.invalidRequest())
                        : relayService.relay(request, protocol, body))
                .onFailure(error -> writeFailure(request, protocol, error));
    }

    private String protocolFor(HttpServerRequest request) {
        if (request.method() != HttpMethod.POST) return null;
        return switch (request.path()) {
            case "/v1/chat/completions" -> "OPENAI";
            case "/v1/messages" -> "ANTHROPIC";
            default -> null;
        };
    }

    private void writeFailure(HttpServerRequest request, String protocol, Throwable error) {
        GatewayFailure failure = error instanceof GatewayFailure known ? known : GatewayFailure.runtimeUnavailable();
        switch (failure.type()) {
            case INVALID_REQUEST -> protocolError(request, protocol, 400, "请求内容不符合要求");
            case INVALID_API_KEY -> protocolError(request, protocol, 401, "API Key 无效或已失效");
            case ACCOUNT_UNAVAILABLE -> protocolError(request, protocol, 403, "当前账号或租户不可用");
            case MODEL_UNAVAILABLE -> protocolError(request, protocol, 503, "当前模型暂不可用，请稍后重试");
            case RUNTIME_UNAVAILABLE -> protocolError(request, protocol, 503, "服务配置暂不可用，请稍后重试");
            case UNSUPPORTED -> protocolError(request, protocol, 501, "当前服务暂不支持该请求");
            case INSUFFICIENT_BALANCE -> protocolError(request, protocol, 402, "当前可用余额不足，请充值后重试");
            case BILLING_SERVICE_UNAVAILABLE -> protocolError(request, protocol, 503, "计费服务暂不可用，请稍后重试");
            case BILLING_RESERVATION_CONFLICT -> protocolError(request, protocol, 409, "请求计费状态冲突，请稍后重试");
            case BILLING_RESERVATION_UNSUPPORTED -> protocolError(request, protocol, 400, "请求无法安全计算预冻结金额，请调整后重试");
        }
    }

    private boolean contentLengthExceedsLimit(HttpServerRequest request) {
        String header = request.getHeader("content-length");
        if (header == null) return false;
        try { return Long.parseLong(header) > runtimeConfig.maxRequestBodyBytes(); }
        catch (NumberFormatException ignored) { return false; }
    }

    private void protocolError(HttpServerRequest request, String protocol, int status, String message) {
        json(request, status, relayService.error(protocol, message));
    }

    private void safeError(HttpServerRequest request, int status, String message) {
        json(request, status, new JsonObject().put("error", new JsonObject().put("message", message)));
    }

    private void json(HttpServerRequest request, int status, JsonObject body) {
        request.response().setStatusCode(status).putHeader("content-type", "application/json").end(body.encode());
    }

    private String apiKey(HttpServerRequest request) {
        String authorization = request.getHeader("Authorization");
        return authorization != null && authorization.regionMatches(true, 0, "Bearer ", 0, 7)
                ? authorization.substring(7) : request.getHeader("x-api-key");
    }
}
