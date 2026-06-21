package io.fluxora.gateway;

import io.vertx.core.Future;
import io.vertx.core.Vertx;
import io.vertx.core.http.HttpServer;

/**
 * 网关当前阶段仅暴露进程存活检查；业务中继能力在后续阶段接入。
 */
public final class HealthHttpServer {

    private final Vertx vertx;
    private HttpServer server;

    public HealthHttpServer(Vertx vertx) {
        this.vertx = vertx;
    }

    public Future<Integer> start(int port) {
        return vertx.createHttpServer()
                .requestHandler(request -> {
                    if (request.method().name().equals("GET") && request.path().equals("/health")) {
                        request.response()
                                .putHeader("content-type", "application/json")
                                .end("{\"status\":\"UP\"}");
                        return;
                    }
                    request.response().setStatusCode(404).end();
                })
                .listen(port)
                .onSuccess(httpServer -> server = httpServer)
                .map(HttpServer::actualPort);
    }

    public Future<Void> close() {
        return server == null ? Future.succeededFuture() : server.close();
    }
}
