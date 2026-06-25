package io.fluxora.gateway.relay.orchestration;

import io.fluxora.gateway.relay.failure.UpstreamSignal;
import io.fluxora.gateway.relay.scheduling.DispatchPlan;

/** Attempt 失败的结构化异常；只携带安全分类输入，不包含上游原始错误正文。 */
public final class AttemptFailure extends RuntimeException {
    private final UpstreamSignal signal;
    private final AttemptStateSnapshot snapshot;
    private final DispatchPlan plan;

    public AttemptFailure(UpstreamSignal signal, AttemptStateSnapshot snapshot) {
        this(signal, snapshot, null);
    }

    public AttemptFailure(UpstreamSignal signal, AttemptStateSnapshot snapshot, DispatchPlan plan) {
        super("upstream attempt failed");
        this.signal = signal;
        this.snapshot = snapshot;
        this.plan = plan;
    }

    public UpstreamSignal signal() { return signal; }
    public AttemptStateSnapshot snapshot() { return snapshot; }
    public DispatchPlan plan() { return plan; }
}
