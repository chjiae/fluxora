package io.fluxora.gateway.relay.orchestration;

/** 单次 Attempt 的状态机，唯一修改写入/响应/提交状态的组件。 */
public final class AttemptStateMachine {
    private AttemptStateSnapshot snapshot = AttemptStateSnapshot.initial();

    public void markRequestFullySent() {
        snapshot = snapshot.withRequestWriteState(RequestWriteState.FULLY_SENT);
    }

    public void markPreExecutionRejected() {
        snapshot = snapshot.preExecutionRejected();
    }

    public void markExecutionMarkerReceived() {
        snapshot = snapshot.possiblyExecuted();
    }

    public void markClientCommitted() {
        snapshot = snapshot.committed();
    }

    public AttemptStateSnapshot snapshot() { return snapshot; }
}
