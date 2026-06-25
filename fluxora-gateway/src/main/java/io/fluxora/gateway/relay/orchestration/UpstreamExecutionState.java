package io.fluxora.gateway.relay.orchestration;

/** 上游是否可能已经执行；这是禁止重复扣费、禁止拼接响应的核心安全边界。 */
public enum UpstreamExecutionState {
    NOT_EXECUTED,
    PRE_EXECUTION_REJECTED,
    POSSIBLY_EXECUTED,
    STREAMING,
    COMPLETED
}
