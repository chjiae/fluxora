package io.fluxora.gateway.relay.failure;

import io.fluxora.gateway.relay.orchestration.AttemptStateSnapshot;
import io.vertx.core.json.JsonObject;

/** OpenAI 兼容错误解析，只读取 error.type / error.code 等稳定结构化字段。 */
public final class OpenAiCompatibleFailureClassifier implements FailureClassifier {
    @Override
    public boolean supports(UpstreamSignal signal) {
        return (signal.kind() == UpstreamSignal.Kind.HTTP || signal.kind() == UpstreamSignal.Kind.SSE)
                && "OPENAI".equals(signal.protocol());
    }

    @Override
    public FailureClassification classify(UpstreamSignal signal, AttemptStateSnapshot state) {
        JsonObject error = signal.structuredBody().getJsonObject("error");
        String code = error == null ? null : error.getString("code");
        String type = error == null ? null : error.getString("type");
        if ("invalid_api_key".equals(code) || "authentication_error".equals(type)) {
            return new FailureClassification(FailureKind.AUTH_INVALID, FailureScope.CREDENTIAL,
                    ExecutionCertainty.PRE_EXECUTION_REJECTED, CooldownAdvice.none());
        }
        if ("insufficient_quota".equals(code) || "insufficient_quota".equals(type)) {
            return new FailureClassification(FailureKind.UPSTREAM_BILLING_EXHAUSTED, FailureScope.BILLING_ACCOUNT_GROUP,
                    ExecutionCertainty.PRE_EXECUTION_REJECTED, CooldownAdvice.none());
        }
        if ("rate_limit_exceeded".equals(code) || signal.httpStatus() != null && signal.httpStatus() == 429) {
            return new FailureClassification(FailureKind.RATE_LIMITED, FailureScope.QUOTA_SCOPE,
                    ExecutionCertainty.PRE_EXECUTION_REJECTED, CooldownAdvice.retryAfter(signal.retryAfterMs() == null ? 0L : signal.retryAfterMs()));
        }
        if (signal.httpStatus() != null && (signal.httpStatus() == 400 || signal.httpStatus() == 422)) {
            return new FailureClassification(FailureKind.CLIENT_REQUEST_INVALID, FailureScope.CLIENT,
                    ExecutionCertainty.NOT_EXECUTED, CooldownAdvice.none());
        }
        return FailureClassification.unknown(ExecutionCertainty.POSSIBLY_EXECUTED);
    }
}
