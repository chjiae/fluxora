package io.fluxora.gateway.observability;

/** Gateway 对 Platform 的请求生命周期事件类型。 */
public enum RelayEventType {
    RELAY_REQUEST_STARTED,
    RELAY_REQUEST_FINISHED,
    RELAY_REQUEST_FAILED,
    RELAY_REQUEST_CANCELLED
}
