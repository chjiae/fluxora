package io.fluxora.gateway.relay.orchestration;

import io.fluxora.gateway.GatewayFailure;
import io.fluxora.gateway.relay.failure.FailureClassification;
import io.fluxora.gateway.relay.failure.FailureClassifierRegistry;
import io.fluxora.gateway.relay.retry.DefaultRetryPolicy;
import io.fluxora.gateway.relay.retry.RetryContext;
import io.fluxora.gateway.relay.retry.RetryDecision;
import io.fluxora.gateway.relay.retry.RetryDirective;
import io.fluxora.gateway.relay.retry.RetryPolicy;
import io.fluxora.gateway.relay.runtime.LocalRuntimeQuarantine;
import io.fluxora.gateway.relay.runtime.RuntimeFailureReporter;
import io.fluxora.gateway.relay.runtime.RuntimeIncident;
import io.fluxora.gateway.relay.runtime.RuntimeIncidentMapper;
import io.fluxora.gateway.relay.scheduling.DispatchPlan;
import io.fluxora.gateway.relay.scheduling.UpstreamDispatchPlanner;
import io.vertx.core.Future;
import io.vertx.core.Promise;

/**
 * 唯一 Attempt 编排入口。
 *
 * 该类只串联：调度 → 单次执行 → 分类 → 纯重试决策 → 释放租约 → 下一次调度。
 * 它不解析协议正文、不选择具体 Credential 之外的业务语义、不写余额状态。
 */
public final class RelayAttemptCoordinator {
    private final UpstreamDispatchPlanner planner;
    private final FailureClassifierRegistry classifierRegistry;
    private final RetryPolicy retryPolicy;
    private final RuntimeIncidentMapper incidentMapper;
    private final LocalRuntimeQuarantine localQuarantine;
    private final RuntimeFailureReporter failureReporter;

    public RelayAttemptCoordinator(UpstreamDispatchPlanner planner) {
        this(planner, FailureClassifierRegistry.defaultRegistry(), new DefaultRetryPolicy(),
                new RuntimeIncidentMapper(), new LocalRuntimeQuarantine(), RuntimeFailureReporter.noop());
    }

    public RelayAttemptCoordinator(UpstreamDispatchPlanner planner, FailureClassifierRegistry classifierRegistry,
                                   RetryPolicy retryPolicy) {
        this(planner, classifierRegistry, retryPolicy, new RuntimeIncidentMapper(),
                new LocalRuntimeQuarantine(), RuntimeFailureReporter.noop());
    }

    public RelayAttemptCoordinator(UpstreamDispatchPlanner planner, FailureClassifierRegistry classifierRegistry,
                                   RetryPolicy retryPolicy, RuntimeIncidentMapper incidentMapper,
                                   LocalRuntimeQuarantine localQuarantine,
                                   RuntimeFailureReporter failureReporter) {
        this.planner = planner;
        this.classifierRegistry = classifierRegistry;
        this.retryPolicy = retryPolicy;
        this.incidentMapper = incidentMapper;
        this.localQuarantine = localQuarantine;
        this.failureReporter = failureReporter;
    }

    public Future<Void> execute(RelayAttemptContext context, AttemptExecutor executor) {
        Promise<Void> promise = Promise.promise();
        executeNext(context, executor, promise);
        return promise.future();
    }

    private void executeNext(RelayAttemptContext context, AttemptExecutor executor, Promise<Void> promise) {
        DispatchPlan plan = context.consumeFirstPlan();
        Future<DispatchPlan> planned = plan == null
                ? planner.planAndAcquire(context.routeSnapshot(), context.exclusions(), context.requestId(), context.nextAttemptId())
                : Future.succeededFuture(plan);
        planned.compose(currentPlan -> executeSingleAttempt(currentPlan, executor))
                .onSuccess(promise::complete)
                .onFailure(error -> handleFailure(context, executor, promise, error));
    }

    /** 执行单次 Attempt：独立状态机驱动请求写入、上游响应与提交屏障判定，并在结束后释放该次租约。 */
    private Future<Void> executeSingleAttempt(DispatchPlan currentPlan, AttemptExecutor executor) {
        // 每次执行独立的状态机：单次 Attempt 的请求写入、上游响应、提交屏障判定互不串扰
        AttemptStateMachine stateMachine = new AttemptStateMachine();
        return executor.execute(currentPlan, stateMachine)
                // 无论本次执行成功或失败，都必须释放该 Attempt 持有的调度租约，避免租约泄漏阻塞后续调度
                .eventually(() -> planner.release(currentPlan.lease()));
    }

    private void handleFailure(RelayAttemptContext context, AttemptExecutor executor, Promise<Void> promise, Throwable error) {
        if (!(error instanceof AttemptFailure attemptFailure)) {
            promise.fail(error);
            return;
        }
        FailureClassification classification = classifierRegistry.classify(attemptFailure.signal(), attemptFailure.snapshot());
        if (attemptFailure.plan() != null) {
            incidentMapper.map(context.requestId(), context.attemptNo(), attemptFailure.plan(), classification,
                            attemptFailure.signal())
                    .ifPresent(incident -> handleRuntimeIncident(context, incident));
        }
        RetryDecision decision = retryPolicy.decide(new RetryContext(context.attemptNo(), context.maxAttempts(),
                context.remainingFirstByteBudgetMs(), attemptFailure.snapshot().clientCommitState() == ClientCommitState.COMMITTED),
                classification);
        if (decision instanceof RetryDecision.Retry retry && context.advanceAttempt()) {
            applyDirective(context, retry.directive(), attemptFailure);
            executeNext(context, executor, promise);
            return;
        }
        if (decision instanceof RetryDecision.ReconcileThenFail) {
            promise.fail(GatewayFailure.modelUnavailable());
        } else if (decision instanceof RetryDecision.Fail fail) {
            promise.fail(GatewayFailure.modelUnavailable());
        } else {
            promise.fail(GatewayFailure.modelUnavailable());
        }
    }

    private void handleRuntimeIncident(RelayAttemptContext context, RuntimeIncident incident) {
        localQuarantine.apply(incident);
        failureReporter.report(context.tenantId(), incident);
    }

    private void applyDirective(RelayAttemptContext context, RetryDirective directive, AttemptFailure failure) {
        if (directive instanceof RetryDirective.ExcludeCredential) {
            Long id = failure.signal().structuredBody() == null ? null : failure.signal().structuredBody().getLong("providerCredentialId");
            if (id != null) context.exclusions().excludeCredential(id);
        } else if (directive instanceof RetryDirective.ExcludeProviderChannel) {
            Long id = failure.signal().structuredBody() == null ? null : failure.signal().structuredBody().getLong("providerChannelId");
            if (id != null) context.exclusions().excludeProviderChannel(id);
        } else if (directive instanceof RetryDirective.ExcludeRouteTarget) {
            Long id = failure.signal().structuredBody() == null ? null : failure.signal().structuredBody().getLong("routeTargetId");
            if (id != null) context.exclusions().excludeRouteTarget(id);
        } else if (directive instanceof RetryDirective.ExcludeQuotaScope) {
            String scope = failure.signal().structuredBody() == null ? null : failure.signal().structuredBody().getString("quotaScope");
            context.exclusions().excludeQuotaScope(scope);
        } else if (directive instanceof RetryDirective.ExcludeBillingAccountGroup) {
            String group = failure.signal().structuredBody() == null ? null : failure.signal().structuredBody().getString("billingAccountGroup");
            context.exclusions().excludeBillingAccountGroup(group);
        } else if (directive instanceof RetryDirective.ExcludeProviderChannelCredential) {
            Long id = failure.signal().structuredBody() == null ? null : failure.signal().structuredBody().getLong("providerChannelCredentialId");
            if (id != null) context.exclusions().excludeProviderChannelCredential(id);
        }
    }
}
