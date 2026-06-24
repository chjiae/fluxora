package io.fluxora.gateway.relay;

import io.fluxora.gateway.GatewayFailure;
import io.vertx.core.buffer.Buffer;
import io.vertx.core.json.JsonObject;

/** 协议 Handler 只处理 model 往返改写和该协议的安全错误载荷。 */
public interface RelayHandler {
    JsonObject upstreamRequest(JsonObject inbound, String upstreamModelId);
    JsonObject clientResponse(JsonObject upstream, String tenantModelCode);
    JsonObject clientSseData(JsonObject upstream, String tenantModelCode);
    JsonObject error(String message);
    Buffer streamError(String message);
    String endpoint();
    default GatewayFailure failure() { return GatewayFailure.modelUnavailable(); }
}
