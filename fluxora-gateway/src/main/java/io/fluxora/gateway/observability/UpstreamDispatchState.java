package io.fluxora.gateway.observability;

/** 终态事件中的安全派发判断；未知时宁可保留冻结待对账，也不得自动释放。 */
public enum UpstreamDispatchState {
    NOT_DISPATCHED,
    DISPATCHED,
    RESPONSE_STARTED,
    UNKNOWN
}
