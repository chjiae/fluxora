package io.fluxora.gateway.relay.retry;

/** RetryPolicy 的纯输入；不携带 Redis、HTTP client 或可执行副作用对象。 */
public record RetryContext(int attemptNo, int maxAttempts, long remainingFirstByteBudgetMs, boolean clientCommitted) {
    public boolean hasBudget() { return attemptNo < maxAttempts && remainingFirstByteBudgetMs > 0L && !clientCommitted; }
}
