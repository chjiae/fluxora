package io.fluxora.gateway.relay.runtime;

import io.fluxora.gateway.relay.failure.CooldownAdvice;
import io.fluxora.gateway.relay.failure.ExecutionCertainty;
import io.fluxora.gateway.relay.failure.FailureClassification;
import io.fluxora.gateway.relay.failure.FailureKind;
import io.fluxora.gateway.relay.failure.FailureScope;
import io.fluxora.gateway.relay.scheduling.DispatchCandidate;
import io.fluxora.gateway.relay.scheduling.DispatchLease;
import io.fluxora.gateway.relay.scheduling.DispatchPlan;
import io.fluxora.gateway.route.RouteSelection;
import io.vertx.core.json.JsonObject;
import java.time.Duration;
import java.time.Instant;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class LocalRuntimeQuarantineTest {

    @Test
    void credentialIncidentTemporarilyRejectsOnlyThatCredential() {
        LocalRuntimeQuarantine quarantine = new LocalRuntimeQuarantine();
        DispatchPlan plan = plan(1001L, 11001L);
        quarantine.apply(new RuntimeIncident("req", 1, plan,
                new FailureClassification(FailureKind.AUTH_INVALID, FailureScope.CREDENTIAL,
                        ExecutionCertainty.PRE_EXECUTION_REJECTED, CooldownAdvice.none()),
                Duration.ofSeconds(60), Instant.now(), 401));

        assertFalse(quarantine.accepts(candidate(1001L, 11001L)));
        assertTrue(quarantine.accepts(candidate(1002L, 11002L)));
    }

    private static DispatchPlan plan(long credentialId, long bindingId) {
        JsonObject target = new JsonObject().put("outboundProtocol", "OPENAI").put("upstreamModelId", "u");
        JsonObject route = new JsonObject();
        return new DispatchPlan(new RouteSelection(101L, 11L, 21L, "OPENAI", "u", target, route),
                new JsonObject().put("providerCredentialId", credentialId).put("providerChannelCredentialId", bindingId)
                        .put("quotaScope", "q").put("billingAccountGroup", "b"),
                new DispatchLease("lease", "attempt", 101L, 11L, "q", "b", credentialId,
                        Instant.now().plusSeconds(30)),
                1, "test", "LOCAL");
    }

    private static DispatchCandidate candidate(long credentialId, long bindingId) {
        return new DispatchCandidate(new JsonObject(), new JsonObject(), 1, 1,
                101L, 11L, 21L, bindingId, credentialId, "q", "b", 1, 10);
    }
}
