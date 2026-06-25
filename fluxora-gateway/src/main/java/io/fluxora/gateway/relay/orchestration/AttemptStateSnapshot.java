package io.fluxora.gateway.relay.orchestration;

import io.fluxora.gateway.relay.failure.ExecutionCertainty;

/**
 * Attempt 状态机的不可变快照。
 *
 * retrySafe 只表示“从客户端与协议标记角度仍可考虑重试”，最终是否重试仍由 RetryPolicy
 * 根据失败类型、预算和排除规则集中决策。
 */
public record AttemptStateSnapshot(RequestWriteState requestWriteState,
                                   UpstreamResponseState upstreamResponseState,
                                   UpstreamExecutionState upstreamExecutionState,
                                   ClientCommitState clientCommitState,
                                   ExecutionCertainty executionCertainty,
                                   boolean retrySafe) {
    public static AttemptStateSnapshot initial() {
        return new AttemptStateSnapshot(RequestWriteState.NOT_SENT, UpstreamResponseState.NO_RESPONSE,
                UpstreamExecutionState.NOT_EXECUTED, ClientCommitState.NOT_COMMITTED,
                ExecutionCertainty.NOT_EXECUTED, true);
    }

    public AttemptStateSnapshot withRequestWriteState(RequestWriteState state) {
        ExecutionCertainty certainty = state == RequestWriteState.NOT_SENT ? executionCertainty : ExecutionCertainty.POSSIBLY_EXECUTED;
        return new AttemptStateSnapshot(state, upstreamResponseState, upstreamExecutionState, clientCommitState,
                certainty, retrySafe && state == RequestWriteState.NOT_SENT);
    }

    public AttemptStateSnapshot preExecutionRejected() {
        return new AttemptStateSnapshot(requestWriteState, UpstreamResponseState.STREAM_ERROR_BEFORE_EXECUTION,
                UpstreamExecutionState.PRE_EXECUTION_REJECTED, clientCommitState,
                ExecutionCertainty.PRE_EXECUTION_REJECTED, clientCommitState == ClientCommitState.NOT_COMMITTED);
    }

    public AttemptStateSnapshot possiblyExecuted() {
        return new AttemptStateSnapshot(requestWriteState, UpstreamResponseState.EXECUTION_MARKER_RECEIVED,
                UpstreamExecutionState.POSSIBLY_EXECUTED, clientCommitState,
                ExecutionCertainty.POSSIBLY_EXECUTED, false);
    }

    public AttemptStateSnapshot committed() {
        return new AttemptStateSnapshot(requestWriteState, upstreamResponseState, upstreamExecutionState,
                ClientCommitState.COMMITTED, executionCertainty, false);
    }
}
