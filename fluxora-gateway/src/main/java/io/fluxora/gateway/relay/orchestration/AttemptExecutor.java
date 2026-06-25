package io.fluxora.gateway.relay.orchestration;

import io.fluxora.gateway.relay.scheduling.DispatchPlan;
import io.vertx.core.Future;

/** 单次上游 Attempt 执行器；不负责重试循环和下一资源选择。 */
@FunctionalInterface
public interface AttemptExecutor {
    Future<Void> execute(DispatchPlan plan, AttemptStateMachine stateMachine);
}
