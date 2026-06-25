package io.fluxora.gateway.relay.retry;

import io.fluxora.gateway.relay.failure.CooldownAdvice;
import io.fluxora.gateway.relay.failure.ExecutionCertainty;
import io.fluxora.gateway.relay.failure.FailureClassification;
import io.fluxora.gateway.relay.failure.FailureKind;
import io.fluxora.gateway.relay.failure.FailureScope;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertInstanceOf;

class DefaultRetryPolicyTest {

    @Test
    void invalidCredentialRetriesByExcludingCredentialOnly() {
        RetryDecision decision = new DefaultRetryPolicy().decide(context(1),
                new FailureClassification(FailureKind.AUTH_INVALID, FailureScope.CREDENTIAL,
                        ExecutionCertainty.PRE_EXECUTION_REJECTED, CooldownAdvice.none()));

        RetryDecision.Retry retry = assertInstanceOf(RetryDecision.Retry.class, decision);
        assertInstanceOf(RetryDirective.ExcludeCredential.class, retry.directive());
    }

    @Test
    void possibleExecutionNeverRetriesAndRequiresReconciliation() {
        RetryDecision decision = new DefaultRetryPolicy().decide(context(1),
                new FailureClassification(FailureKind.NETWORK_PARTIAL_SEND_FAILURE, FailureScope.UNKNOWN,
                        ExecutionCertainty.POSSIBLY_EXECUTED, CooldownAdvice.none()));

        assertInstanceOf(RetryDecision.ReconcileThenFail.class, decision);
    }

    @Test
    void possibleExecutionAfterClientCommitStillRequiresReconciliation() {
        RetryDecision decision = new DefaultRetryPolicy().decide(new RetryContext(1, 3, 5_000L, true),
                new FailureClassification(FailureKind.NETWORK_PARTIAL_SEND_FAILURE, FailureScope.UNKNOWN,
                        ExecutionCertainty.POSSIBLY_EXECUTED, CooldownAdvice.none()));

        assertInstanceOf(RetryDecision.ReconcileThenFail.class, decision);
    }

    @Test
    void providerChannelCredentialFailureRetriesByExcludingThatBinding() {
        RetryDecision decision = new DefaultRetryPolicy().decide(context(1),
                new FailureClassification(FailureKind.AUTH_PERMISSION_DENIED, FailureScope.PROVIDER_CHANNEL_CREDENTIAL,
                        ExecutionCertainty.PRE_EXECUTION_REJECTED, CooldownAdvice.none()));

        RetryDecision.Retry retry = assertInstanceOf(RetryDecision.Retry.class, decision);
        assertInstanceOf(RetryDirective.ExcludeProviderChannelCredential.class, retry.directive());
    }

    @Test
    void exhaustedBudgetFailsWithoutRetry() {
        RetryDecision decision = new DefaultRetryPolicy().decide(context(3),
                new FailureClassification(FailureKind.AUTH_INVALID, FailureScope.CREDENTIAL,
                        ExecutionCertainty.PRE_EXECUTION_REJECTED, CooldownAdvice.none()));

        assertInstanceOf(RetryDecision.Fail.class, decision);
    }

    private static RetryContext context(int attemptNo) {
        return new RetryContext(attemptNo, 3, 5_000L, false);
    }
}
