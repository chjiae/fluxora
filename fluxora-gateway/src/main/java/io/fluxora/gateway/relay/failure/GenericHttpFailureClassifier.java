package io.fluxora.gateway.relay.failure;

import io.fluxora.gateway.relay.orchestration.AttemptStateSnapshot;

/** 通用 HTTP 兜底分类；厂商结构化解析失败后才进入这里。 */
public final class GenericHttpFailureClassifier implements FailureClassifier {
    @Override public boolean supports(UpstreamSignal signal) { return signal.kind() == UpstreamSignal.Kind.HTTP; }

    @Override
    public FailureClassification classify(UpstreamSignal signal, AttemptStateSnapshot state) {
        int status = signal.httpStatus() == null ? 0 : signal.httpStatus();
        if (status == 429) {
            return new FailureClassification(FailureKind.RATE_LIMITED, FailureScope.QUOTA_SCOPE,
                    ExecutionCertainty.PRE_EXECUTION_REJECTED, CooldownAdvice.retryAfter(signal.retryAfterMs() == null ? 0L : signal.retryAfterMs()));
        }
        if (status == 401) {
            return new FailureClassification(FailureKind.AUTH_INVALID, FailureScope.CREDENTIAL,
                    ExecutionCertainty.PRE_EXECUTION_REJECTED, CooldownAdvice.none());
        }
        if (status == 403) {
            return new FailureClassification(FailureKind.AUTH_PERMISSION_DENIED, FailureScope.PROVIDER_CHANNEL_CREDENTIAL,
                    ExecutionCertainty.PRE_EXECUTION_REJECTED, CooldownAdvice.none());
        }
        if (status == 404) {
            return new FailureClassification(FailureKind.MODEL_MAPPING_INVALID, FailureScope.ROUTE_TARGET,
                    ExecutionCertainty.PRE_EXECUTION_REJECTED, CooldownAdvice.none());
        }
        if (status == 400 || status == 422) {
            return new FailureClassification(FailureKind.CLIENT_REQUEST_INVALID, FailureScope.CLIENT,
                    ExecutionCertainty.NOT_EXECUTED, CooldownAdvice.none());
        }
        if (status == 500 || status == 503 || status == 529) {
            return new FailureClassification(FailureKind.UPSTREAM_SERVER_ERROR, FailureScope.PROVIDER_CHANNEL,
                    ExecutionCertainty.POSSIBLY_EXECUTED, CooldownAdvice.none());
        }
        return FailureClassification.unknown(ExecutionCertainty.POSSIBLY_EXECUTED);
    }
}
