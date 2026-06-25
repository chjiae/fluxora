package io.fluxora.gateway.relay.retry;

import io.fluxora.gateway.relay.failure.FailureClassification;

/** 纯重试决策接口；实现不得发请求、写 Redis、更新状态或选择具体 Credential。 */
public interface RetryPolicy {
    RetryDecision decide(RetryContext context, FailureClassification failure);
}
