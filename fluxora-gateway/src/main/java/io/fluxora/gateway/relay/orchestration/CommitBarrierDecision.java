package io.fluxora.gateway.relay.orchestration;

import io.fluxora.gateway.relay.failure.UpstreamSignal;
import java.util.Optional;

/** 提交屏障的输出：要么允许提交客户端，要么返回可分类的预执行失败。 */
public record CommitBarrierDecision(boolean shouldCommit, Optional<UpstreamSignal> failureSignal) {
    public static CommitBarrierDecision hold() { return new CommitBarrierDecision(false, Optional.empty()); }
    public static CommitBarrierDecision commit() { return new CommitBarrierDecision(true, Optional.empty()); }
    public static CommitBarrierDecision failure(UpstreamSignal signal) { return new CommitBarrierDecision(false, Optional.of(signal)); }
}
