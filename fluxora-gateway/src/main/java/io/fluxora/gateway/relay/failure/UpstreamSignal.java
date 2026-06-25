package io.fluxora.gateway.relay.failure;

import io.fluxora.gateway.relay.orchestration.RequestWriteState;
import io.vertx.core.json.JsonObject;

/** 上游失败的安全、结构化输入；不得携带完整原始错误正文或任何凭证。 */
public record UpstreamSignal(Kind kind, String protocol, Throwable transportError,
                             RequestWriteState requestWriteState, Integer httpStatus,
                             String contentType, JsonObject structuredBody, Long retryAfterMs) {
    public enum Kind { TRANSPORT, HTTP, SSE }

    public static UpstreamSignal transport(Throwable error, RequestWriteState writeState) {
        return transport(error, writeState, new JsonObject());
    }

    public static UpstreamSignal transport(Throwable error, RequestWriteState writeState, JsonObject body) {
        return new UpstreamSignal(Kind.TRANSPORT, null, error, writeState, null, null,
                body == null ? new JsonObject() : body, null);
    }

    public static UpstreamSignal http(String protocol, int status, String contentType, JsonObject body, Long retryAfterMs) {
        return new UpstreamSignal(Kind.HTTP, protocol, null, RequestWriteState.FULLY_SENT, status,
                contentType, body == null ? new JsonObject() : body, retryAfterMs);
    }

    public static UpstreamSignal sse(String protocol, JsonObject event) {
        return new UpstreamSignal(Kind.SSE, protocol, null, RequestWriteState.FULLY_SENT, 200,
                "text/event-stream", event == null ? new JsonObject() : event, null);
    }
}
