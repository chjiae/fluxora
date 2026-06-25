package io.fluxora.gateway.relay.failure;

/** 上游执行确定性；重试策略只信这个稳定状态，不根据错误文案猜测。 */
public enum ExecutionCertainty {
    NOT_EXECUTED,
    PRE_EXECUTION_REJECTED,
    POSSIBLY_EXECUTED
}
