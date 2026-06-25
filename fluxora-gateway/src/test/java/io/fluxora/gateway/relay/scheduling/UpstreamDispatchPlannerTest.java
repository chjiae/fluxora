package io.fluxora.gateway.relay.scheduling;

import io.vertx.core.Future;
import io.vertx.core.json.JsonArray;
import io.vertx.core.json.JsonObject;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class UpstreamDispatchPlannerTest {

    @Test
    void backupTierDoesNotReceiveNormalTrafficWhenPrimaryTierAvailable() throws Exception {
        UpstreamDispatchPlanner planner = new UpstreamDispatchPlanner(new InMemoryDispatchLeaseManager());
        JsonObject route = route(
                target(101, 11, 1, 100, credentials(1001)),
                target(201, 21, 2, 100, credentials(2001)));

        for (int i = 0; i < 20; i++) {
            DispatchPlan plan = await(planner.planAndAcquire(route, DispatchExclusions.none(), "req", "a-" + i));
            assertEquals(1, plan.priorityTier());
            await(planner.release(plan.lease()));
        }
    }

    @Test
    void multipleRouteTargetsDoNotAmplifyOneChannelShare() throws Exception {
        UpstreamDispatchPlanner planner = new UpstreamDispatchPlanner(new InMemoryDispatchLeaseManager());
        JsonObject route = route(
                target(101, 11, 1, 100, credentials(1001)),
                target(102, 11, 1, 100, credentials(1002)),
                target(201, 21, 1, 100, credentials(2001)));

        Map<Long, Integer> channelHits = new HashMap<>();
        for (int i = 0; i < 40; i++) {
            DispatchPlan plan = await(planner.planAndAcquire(route, DispatchExclusions.none(), "req", "a-" + i));
            channelHits.merge(plan.providerChannelId(), 1, Integer::sum);
            await(planner.release(plan.lease()));
        }

        assertTrue(Math.abs(channelHits.get(11L) - channelHits.get(21L)) <= 4,
                "channel hits should stay balanced: " + channelHits);
    }

    @Test
    void credentialsInsideSameQuotaScopeAreBalanced() throws Exception {
        UpstreamDispatchPlanner planner = new UpstreamDispatchPlanner(new InMemoryDispatchLeaseManager());
        JsonObject route = route(target(101, 11, 1, 100, credentials(1001, 1002)));

        Map<Long, Integer> credentialHits = new HashMap<>();
        for (int i = 0; i < 30; i++) {
            DispatchPlan plan = await(planner.planAndAcquire(route, DispatchExclusions.none(), "req", "a-" + i));
            credentialHits.merge(plan.providerCredentialId(), 1, Integer::sum);
            await(planner.release(plan.lease()));
        }

        assertTrue(Math.abs(credentialHits.get(1001L) - credentialHits.get(1002L)) <= 2,
                "credential hits should stay balanced: " + credentialHits);
    }

    @Test
    void excludedCredentialIsNotSelectedAgainInSameRequest() throws Exception {
        UpstreamDispatchPlanner planner = new UpstreamDispatchPlanner(new InMemoryDispatchLeaseManager());
        JsonObject route = route(target(101, 11, 1, 100, credentials(1001, 1002)));

        DispatchPlan plan = await(planner.planAndAcquire(route,
                DispatchExclusions.none().excludeCredential(1001L), "req", "a-1"));

        assertEquals(1002L, plan.providerCredentialId());
    }

    @Test
    void excludedProviderChannelCredentialBindingIsNotSelectedAgainInSameRequest() throws Exception {
        UpstreamDispatchPlanner planner = new UpstreamDispatchPlanner(new InMemoryDispatchLeaseManager());
        JsonObject route = route(target(101, 11, 1, 100, credentials(1001, 1002)));

        DispatchPlan plan = await(planner.planAndAcquire(route,
                DispatchExclusions.none().excludeProviderChannelCredential(11001L), "req", "a-1"));

        assertEquals(11002L, plan.providerChannelCredentialId());
    }

    private static JsonObject route(JsonObject... targets) {
        JsonArray array = new JsonArray();
        for (JsonObject target : targets) array.add(target);
        return new JsonObject().put("targets", array);
    }

    private static JsonObject target(long targetId, long channelId, int priority, int weight, JsonArray credentials) {
        return new JsonObject()
                .put("routeTargetId", targetId)
                .put("providerChannelId", channelId)
                .put("providerChannelModelId", channelId * 10)
                .put("priority", priority)
                .put("weight", weight)
                .put("outboundProtocol", "OPENAI")
                .put("upstreamModelId", "upstream-" + targetId)
                .put("targetStatus", "ENABLED")
                .put("mappingStatus", "ENABLED")
                .put("candidateStatus", "ENABLED")
                .put("channelStatus", "ENABLED")
                .put("hasUsableCredential", true)
                .put("credentialRefs", credentials);
    }

    private static JsonArray credentials(long... ids) {
        JsonArray refs = new JsonArray();
        for (long id : ids) {
            refs.add(new JsonObject()
                    .put("providerCredentialId", id)
                    .put("providerChannelCredentialId", id + 10_000)
                    .put("credentialVersion", 1)
                    .put("authType", "BEARER")
                    .put("quotaScope", "q-" + (id % 2))
                    .put("billingAccountGroup", "g-" + (id % 2))
                    .put("trafficWeight", 100)
                    .put("maxConcurrentStreams", 10));
        }
        return refs;
    }

    private static <T> T await(Future<T> future) throws Exception {
        return future.toCompletionStage().toCompletableFuture().get(10, TimeUnit.SECONDS);
    }
}
