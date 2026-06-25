package io.fluxora.gateway.relay.retry;

/** 重试策略的唯一输出；类型本身表达动作，避免多个布尔值互相矛盾。 */
public sealed interface RetryDecision permits RetryDecision.Retry, RetryDecision.Fail, RetryDecision.ReconcileThenFail {
    record Retry(RetryDirective directive, String reason) implements RetryDecision {}
    record Fail(String safeMessage) implements RetryDecision {}
    record ReconcileThenFail(String safeMessage) implements RetryDecision {}
}
