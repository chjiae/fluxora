package io.fluxora.gateway;

import io.vertx.core.Vertx;

/**
 * Fluxora 网关进程入口。当前仅启动 HTTP 健康检查，不连接 Redis 或任何数据库。
 */
public final class FluxoraGatewayApplication {

    private static final int DEFAULT_PORT = 8081;

    private FluxoraGatewayApplication() {
    }

    public static void main(String[] args) {
        Vertx vertx = Vertx.vertx();
        HealthHttpServer server = new HealthHttpServer(vertx);
        server.start(resolvePort())
                .onSuccess(port -> System.out.println("Fluxora gateway listening on port " + port))
                .onFailure(error -> {
                    error.printStackTrace();
                    vertx.close();
                });
    }

    private static int resolvePort() {
        String configuredPort = System.getenv("GATEWAY_PORT");
        return configuredPort == null || configuredPort.isBlank()
                ? DEFAULT_PORT
                : Integer.parseInt(configuredPort);
    }
}
