package io.fluxora.gateway.relay;

import io.fluxora.gateway.GatewayFailure;
import io.vertx.core.buffer.Buffer;
import io.vertx.core.json.JsonObject;

/** 协议 Handler 只处理 model 往返改写和该协议的安全错误载荷。 */
public interface RelayHandler {
    JsonObject upstreamRequest(JsonObject inbound, String upstreamModelId);
    JsonObject clientResponse(JsonObject upstream, String tenantModelCode);
    JsonObject clientSseData(JsonObject upstream, String tenantModelCode);
    /** 从当前协议的一次 JSON 响应或一个 SSE data 事件中提取安全 usage 元数据。 */
    RelayUsage extractUsage(JsonObject upstream);
    /** SSE 是否已到达该协议定义的正常终止事件。 */
    default boolean isTerminalSseEvent(JsonObject upstream) { return false; }
    JsonObject error(String message);
    Buffer streamError(String message);
    String endpoint();
    default GatewayFailure failure() { return GatewayFailure.modelUnavailable(); }
}
