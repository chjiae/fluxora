package io.fluxora.gateway.relay.failure;

import io.fluxora.gateway.relay.orchestration.AttemptStateSnapshot;
import io.vertx.core.json.JsonObject;

/** Anthropic 错误解析只依赖 error.type 等结构化字段，不依赖 message 文案。 */
public final class AnthropicFailureClassifier implements FailureClassifier {
    @Override
    public boolean supports(UpstreamSignal signal) {
        return (signal.kind() == UpstreamSignal.Kind.HTTP || signal.kind() == UpstreamSignal.Kind.SSE)
                && "ANTHROPIC".equals(signal.protocol());
    }

    @Override
    public FailureClassification classify(UpstreamSignal signal, AttemptStateSnapshot state) {
        JsonObject error = signal.structuredBody().getJsonObject("error");
        String type = error == null ? signal.structuredBody().getString("type") : error.getString("type");
        if ("authentication_error".equals(type)) {
            return new FailureClassification(FailureKind.AUTH_INVALID, FailureScope.CREDENTIAL,
                    ExecutionCertainty.PRE_EXECUTION_REJECTED, CooldownAdvice.none());
        }
        if ("permission_error".equals(type)) {
            return new FailureClassification(FailureKind.AUTH_PERMISSION_DENIED, FailureScope.PROVIDER_CHANNEL_CREDENTIAL,
                    ExecutionCertainty.PRE_EXECUTION_REJECTED, CooldownAdvice.none());
        }
        if ("billing_error".equals(type) || "insufficient_quota".equals(type) || "budget_exceeded".equals(type)) {
            return new FailureClassification(FailureKind.UPSTREAM_BILLING_EXHAUSTED, FailureScope.BILLING_ACCOUNT_GROUP,
                    ExecutionCertainty.PRE_EXECUTION_REJECTED, CooldownAdvice.none());
        }
        if ("rate_limit_error".equals(type)) {
            return new FailureClassification(FailureKind.RATE_LIMITED, FailureScope.QUOTA_SCOPE,
                    ExecutionCertainty.PRE_EXECUTION_REJECTED, CooldownAdvice.retryAfter(signal.retryAfterMs() == null ? 0L : signal.retryAfterMs()));
        }
        return FailureClassification.unknown(ExecutionCertainty.POSSIBLY_EXECUTED);
    }
}
