package io.fluxora.gateway.billing;

import io.fluxora.gateway.GatewayFailure;
import io.fluxora.gateway.GatewayRuntimeConfig;
import io.fluxora.gateway.auth.AuthenticatedPrincipal;
import io.fluxora.gateway.observability.RelayObservationEvent;
import io.fluxora.gateway.observability.RelayPriceSnapshot;
import io.fluxora.gateway.route.RouteSelection;
import io.vertx.core.Future;
import io.vertx.core.MultiMap;
import io.vertx.core.Vertx;
import io.vertx.core.buffer.Buffer;
import io.vertx.core.http.HttpClient;
import io.vertx.core.http.HttpMethod;
import io.vertx.core.http.RequestOptions;
import io.vertx.core.json.JsonObject;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

/** Gateway 到 Platform 的非阻塞、带超时 HMAC 内部预冻结客户端；它不接触 PostgreSQL。 */
public final class PlatformBillingClient {
    private static final String RESERVATIONS_PATH = "/internal/gateway/billing/reservations";
    private final HttpClient client;
    private final GatewayRuntimeConfig config;

    public PlatformBillingClient(Vertx vertx, GatewayRuntimeConfig config) {
        this.client = vertx.createHttpClient();
        this.config = config;
    }

    public Future<Void> reserveBeforeDispatch(AuthenticatedPrincipal principal, RouteSelection selection,
                                              String protocol, String endpoint, TokenReservationPlan plan,
                                              RelayObservationEvent observation) {
        RelayPriceSnapshot price = observation.priceSnapshot();
        JsonObject body = new JsonObject()
                .put("requestId", observation.requestId()).put("tenantId", principal.tenantId())
                .put("userId", principal.userId()).put("apiKeyId", principal.apiKeyId())
                .put("tenantModelId", price.tenantModelId()).put("tenantModelCode", price.tenantModelCode())
                .put("inboundProtocol", protocol).put("endpoint", endpoint)
                .put("requestStartedAt", observation.requestStartedAt().toString()).put("currencyCode", price.currencyCode())
                .put("priceVersion", price.priceVersion()).put("inputPricePerMillion", price.inputPricePerMillion())
                .put("outputPricePerMillion", price.outputPricePerMillion())
                .put("inputTokenCeiling", plan.inputTokenCeiling()).put("outputTokenCeiling", plan.outputTokenCeiling())
                .put("cacheWriteTokenCeiling", plan.cacheWriteTokenCeiling()).put("cacheReadTokenCeiling", plan.cacheReadTokenCeiling())
                .put("reservationAmount", plan.reservationAmount());
        putNullable(body, "cacheWritePricePerMillion", price.cacheWritePricePerMillion());
        putNullable(body, "cacheReadPricePerMillion", price.cacheReadPricePerMillion());
        return invoke(HttpMethod.POST, RESERVATIONS_PATH, observation.requestId(), Buffer.buffer(body.encode()))
                .compose(this::classified).recover(error -> queryAndClassify(observation.requestId()));
    }

    private Future<Void> queryAndClassify(String requestId) {
        return invoke(HttpMethod.GET, RESERVATIONS_PATH + "/" + requestId, requestId, Buffer.buffer())
                .compose(this::classified).recover(error -> Future.failedFuture(GatewayFailure.billingUnavailable()));
    }

    private Future<Void> classified(JsonObject response) {
        return switch (response.getString("status")) {
            case "RESERVED" -> Future.succeededFuture();
            case "RESERVE_REJECTED" -> Future.failedFuture(GatewayFailure.insufficientBalance());
            case "CONFLICT" -> Future.failedFuture(GatewayFailure.billingConflict());
            default -> Future.failedFuture(GatewayFailure.billingUnavailable());
        };
    }

    private Future<JsonObject> invoke(HttpMethod method, String path, String requestId, Buffer body) {
        long timestamp = System.currentTimeMillis();
        MultiMap headers = MultiMap.caseInsensitiveMultiMap().set("content-type", "application/json")
                .set("X-Fluxora-Internal-Request-Id", requestId)
                .set("X-Fluxora-Internal-Timestamp", Long.toString(timestamp))
                .set("X-Fluxora-Internal-Signature", signature(method.name(), path, timestamp, requestId));
        RequestOptions options = new RequestOptions().setMethod(method).setAbsoluteURI(config.platformInternalBaseUrl() + path)
                .setHeaders(headers).setFollowRedirects(false).setConnectTimeout((int) config.billingTimeout().toMillis())
                .setIdleTimeout((int) config.billingTimeout().toMillis());
        return client.request(options).compose(request -> request.send(body))
                .compose(response -> response.body().map(buffer -> {
                    if (response.statusCode() < 200 || response.statusCode() >= 300) throw GatewayFailure.billingUnavailable();
                    return new JsonObject(buffer);
                })).recover(error -> Future.failedFuture(error instanceof GatewayFailure ? error : GatewayFailure.billingUnavailable()));
    }

    private String signature(String method, String path, long timestamp, String requestId) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(config.internalGatewayHmacSecret().getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
            return java.util.HexFormat.of().formatHex(mac.doFinal((method + "\n" + path + "\n" + timestamp + "\n" + requestId)
                    .getBytes(StandardCharsets.UTF_8)));
        } catch (Exception exception) {
            throw GatewayFailure.billingUnavailable();
        }
    }

    private static void putNullable(JsonObject body, String key, String value) {
        if (value == null) body.putNull(key); else body.put(key, value);
    }
}
