package io.fluxora.gateway.relay.failure;

import io.fluxora.gateway.relay.orchestration.AttemptStateSnapshot;
import io.fluxora.gateway.relay.orchestration.RequestWriteState;
import io.vertx.core.json.JsonObject;
import java.net.ConnectException;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class FailureClassifierRegistryTest {

    @Test
    void transportFailureBeforeRequestWriteIsRetryableNetworkFailure() {
        FailureClassification classification = FailureClassifierRegistry.defaultRegistry().classify(
                UpstreamSignal.transport(new ConnectException("refused"), RequestWriteState.NOT_SENT),
                AttemptStateSnapshot.initial());

        assertEquals(FailureKind.NETWORK_PRE_SEND_FAILURE, classification.kind());
        assertEquals(FailureScope.PROVIDER_CHANNEL, classification.scope());
        assertEquals(ExecutionCertainty.NOT_EXECUTED, classification.executionCertainty());
    }

    @Test
    void openAiInvalidKeyUsesStructuredCodeInsteadOfMessageText() {
        JsonObject body = new JsonObject().put("error", new JsonObject()
                .put("type", "authentication_error")
                .put("code", "invalid_api_key")
                .put("message", "供应商文案可以随便变化"));

        FailureClassification classification = FailureClassifierRegistry.defaultRegistry().classify(
                UpstreamSignal.http("OPENAI", 401, "application/json", body, null),
                AttemptStateSnapshot.initial());

        assertEquals(FailureKind.AUTH_INVALID, classification.kind());
        assertEquals(FailureScope.CREDENTIAL, classification.scope());
        assertEquals(ExecutionCertainty.PRE_EXECUTION_REJECTED, classification.executionCertainty());
    }

    @Test
    void openAiBudgetExceededIsAlsoBillingExhausted() {
        JsonObject body = new JsonObject().put("error", new JsonObject()
                .put("type", "budget_exceeded")
                .put("code", "400")
                .put("message", "Credit has been exceeded! Current cost: 240.1228373, Max credit: 100.0"));

        FailureClassification classification = FailureClassifierRegistry.defaultRegistry().classify(
                UpstreamSignal.http("OPENAI", 400, "application/json", body, null),
                AttemptStateSnapshot.initial());

        assertEquals(FailureKind.UPSTREAM_BILLING_EXHAUSTED, classification.kind());
        assertEquals(FailureScope.BILLING_ACCOUNT_GROUP, classification.scope());
        assertEquals(ExecutionCertainty.PRE_EXECUTION_REJECTED, classification.executionCertainty());
    }

    @Test
    void genericRateLimitCarriesCooldownAdvice() {
        FailureClassification classification = FailureClassifierRegistry.defaultRegistry().classify(
                UpstreamSignal.http("OPENAI", 429, "application/json", new JsonObject(), 7_000L),
                AttemptStateSnapshot.initial());

        assertEquals(FailureKind.RATE_LIMITED, classification.kind());
        assertEquals(FailureScope.QUOTA_SCOPE, classification.scope());
        assertEquals(ExecutionCertainty.PRE_EXECUTION_REJECTED, classification.executionCertainty());
        assertEquals(7_000L, classification.cooldownAdvice().retryAfterMs());
    }

    @Test
    void openAiInsufficientQuotaUsesBillingAccountGroupWithoutReadingMessage() {
        JsonObject body = new JsonObject().put("error", new JsonObject()
                .put("type", "insufficient_quota")
                .put("code", "insufficient_quota")
                .put("message", "任意厂商文案都不能参与判断"));

        FailureClassification classification = FailureClassifierRegistry.defaultRegistry().classify(
                UpstreamSignal.http("OPENAI", 429, "application/json", body, null),
                AttemptStateSnapshot.initial());

        assertEquals(FailureKind.UPSTREAM_BILLING_EXHAUSTED, classification.kind());
        assertEquals(FailureScope.BILLING_ACCOUNT_GROUP, classification.scope());
        assertEquals(ExecutionCertainty.PRE_EXECUTION_REJECTED, classification.executionCertainty());
    }
}
