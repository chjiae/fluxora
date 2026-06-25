package io.fluxora.gateway.relay.failure;

/** 失败分类器的唯一输出；分类器不做选路、不重试、不写 Redis。 */
public record FailureClassification(FailureKind kind, FailureScope scope,
                                    ExecutionCertainty executionCertainty,
                                    CooldownAdvice cooldownAdvice) {
    public static FailureClassification unknown(ExecutionCertainty certainty) {
        return new FailureClassification(FailureKind.UNKNOWN, FailureScope.UNKNOWN, certainty, CooldownAdvice.none());
    }
}
