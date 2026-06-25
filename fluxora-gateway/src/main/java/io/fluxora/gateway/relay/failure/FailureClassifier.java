package io.fluxora.gateway.relay.failure;

import io.fluxora.gateway.relay.orchestration.AttemptStateSnapshot;

/** 失败解析器只负责识别失败，不允许执行重试、选路或写运行时状态。 */
public interface FailureClassifier {
    boolean supports(UpstreamSignal signal);
    FailureClassification classify(UpstreamSignal signal, AttemptStateSnapshot state);
}
