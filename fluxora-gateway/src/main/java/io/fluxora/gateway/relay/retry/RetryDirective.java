package io.fluxora.gateway.relay.retry;

/** 下一次尝试的排除意图；真正选哪个资源仍由 UpstreamDispatchPlanner 决定。 */
public sealed interface RetryDirective permits RetryDirective.ExcludeCredential,
        RetryDirective.ExcludeQuotaScope, RetryDirective.ExcludeBillingAccountGroup,
        RetryDirective.ExcludeProviderChannelCredential, RetryDirective.ExcludeRouteTarget,
        RetryDirective.ExcludeProviderChannel {
    record ExcludeCredential() implements RetryDirective {}
    record ExcludeQuotaScope(long retryAfterMs) implements RetryDirective {}
    record ExcludeBillingAccountGroup() implements RetryDirective {}
    record ExcludeProviderChannelCredential() implements RetryDirective {}
    record ExcludeRouteTarget() implements RetryDirective {}
    record ExcludeProviderChannel() implements RetryDirective {}
}
