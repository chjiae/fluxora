package io.fluxora.gateway;

import io.vertx.core.Vertx;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;

class HealthHttpServerTest {

    private Vertx vertx;
    private HealthHttpServer healthHttpServer;

    @BeforeEach
    void setUp() {
        vertx = Vertx.vertx();
        healthHttpServer = new HealthHttpServer(vertx);
    }

    @AfterEach
    void tearDown() throws Exception {
        if (healthHttpServer != null) {
            healthHttpServer.close().toCompletionStage().toCompletableFuture().get(5, TimeUnit.SECONDS);
        }
        if (vertx != null) {
            vertx.close().toCompletionStage().toCompletableFuture().get(5, TimeUnit.SECONDS);
        }
    }

    @Test
    void shouldReturnUpJsonForHealthRequest() throws Exception {
        int port = healthHttpServer.start(0).toCompletionStage().toCompletableFuture().get(5, TimeUnit.SECONDS);
        HttpRequest request = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + "/health"))
                .GET()
                .build();

        HttpResponse<String> response = HttpClient.newHttpClient()
                .send(request, HttpResponse.BodyHandlers.ofString());

        assertEquals(200, response.statusCode());
        assertEquals("application/json", response.headers().firstValue("content-type").orElseThrow());
        assertEquals("{\"status\":\"UP\"}", response.body());
    }
}
