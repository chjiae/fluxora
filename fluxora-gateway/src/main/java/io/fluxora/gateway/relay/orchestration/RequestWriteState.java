package io.fluxora.gateway.relay.orchestration;

/** 本次 Attempt 向上游写请求体的进度；用于判断失败后是否仍可安全无感重试。 */
public enum RequestWriteState {
    NOT_SENT,
    PARTIALLY_SENT,
    FULLY_SENT
}
