package io.fluxora.gateway;

import io.vertx.core.Vertx;

/**
 * Fluxora 网关进程入口：仅连接 Redis 派生快照，不连接 PostgreSQL，也不调用真实上游。
 */
public final class FluxoraGatewayApplication {

    private static final int DEFAULT_PORT = 8081;

    private FluxoraGatewayApplication() {
    }

    public static void main(String[] args) {
        Vertx vertx = Vertx.vertx();
        GatewayRuntime runtime = new GatewayRuntime(vertx, GatewayRuntimeConfig.fromEnvironment());
        runtime.start();
        GatewayHttpServer server = new GatewayHttpServer(vertx, runtime);
        server.start(resolvePort())
                .onSuccess(port -> System.out.println("Fluxora gateway listening on port " + port))
                .onFailure(error -> {
                    System.err.println("Fluxora gateway 启动失败");
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
