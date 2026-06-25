package io.fluxora.gateway.relay.orchestration;

/** 客户端响应是否已经提交；一旦提交，后续任何失败都不得再无感切上游。 */
public enum ClientCommitState {
    NOT_COMMITTED,
    COMMITTED
}
