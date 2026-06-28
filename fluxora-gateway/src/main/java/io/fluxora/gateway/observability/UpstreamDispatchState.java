package io.fluxora.gateway.observability;

/** 终态事件中的安全派发判断；未知时保留人工确认入口，避免错误归类为免扣。 */
public enum UpstreamDispatchState {
    NOT_DISPATCHED,
    DISPATCHED,
    RESPONSE_STARTED,
    UNKNOWN
}
