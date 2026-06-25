package io.fluxora.gateway.relay.retry;

import io.fluxora.gateway.relay.failure.ExecutionCertainty;
import io.fluxora.gateway.relay.failure.FailureClassification;
import io.fluxora.gateway.relay.failure.FailureKind;
import io.fluxora.gateway.relay.failure.FailureScope;

/** 默认 SAFE_ONLY 策略：只有确认未执行或预执行拒绝的失败，才允许无感重试。 */
public final class DefaultRetryPolicy implements RetryPolicy {
    private static final String UNAVAILABLE = "上游服务暂时不可用，请稍后重试";

    @Override
    public RetryDecision decide(RetryContext context, FailureClassification failure) {
        if (failure.executionCertainty() == ExecutionCertainty.POSSIBLY_EXECUTED) {
            return new RetryDecision.ReconcileThenFail(UNAVAILABLE);
        }
        if (!context.hasBudget()) return new RetryDecision.Fail(UNAVAILABLE);
        if (context.clientCommitted()) return new RetryDecision.Fail(UNAVAILABLE);
        if (failure.kind() == FailureKind.CLIENT_REQUEST_INVALID || failure.scope() == FailureScope.CLIENT) {
            return new RetryDecision.Fail("请求参数不合法，请检查后重试");
        }
        return switch (failure.scope()) {
            case CREDENTIAL -> new RetryDecision.Retry(new RetryDirective.ExcludeCredential(), failure.kind().name());
            case PROVIDER_CHANNEL_CREDENTIAL -> new RetryDecision.Retry(
                    new RetryDirective.ExcludeProviderChannelCredential(), failure.kind().name());
            case QUOTA_SCOPE -> new RetryDecision.Retry(
                    new RetryDirective.ExcludeQuotaScope(failure.cooldownAdvice().retryAfterMs()), failure.kind().name());
            case BILLING_ACCOUNT_GROUP -> new RetryDecision.Retry(new RetryDirective.ExcludeBillingAccountGroup(), failure.kind().name());
            case ROUTE_TARGET, PROVIDER_CHANNEL_MODEL -> new RetryDecision.Retry(new RetryDirective.ExcludeRouteTarget(), failure.kind().name());
            case PROVIDER_CHANNEL -> new RetryDecision.Retry(new RetryDirective.ExcludeProviderChannel(), failure.kind().name());
            default -> new RetryDecision.Fail(UNAVAILABLE);
        };
    }
}
