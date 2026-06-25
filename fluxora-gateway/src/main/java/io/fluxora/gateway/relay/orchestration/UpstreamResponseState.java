package io.fluxora.gateway.relay.orchestration;

/** 上游响应阶段；响应头到达不代表模型已经执行，必须结合协议执行标记判断。 */
public enum UpstreamResponseState {
    NO_RESPONSE,
    HTTP_ERROR,
    HEADERS_RECEIVED,
    STREAM_ERROR_BEFORE_EXECUTION,
    EXECUTION_MARKER_RECEIVED,
    STREAM_STARTED,
    COMPLETED
}
