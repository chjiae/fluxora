package io.fluxora.gateway;

import io.fluxora.gateway.auth.ApiKeyLookupHasher;
import io.fluxora.gateway.auth.AuthenticatedPrincipal;
import io.fluxora.gateway.auth.GatewayAuthenticator;
import io.fluxora.gateway.route.GatewayRouteResolver;
import io.fluxora.gateway.route.RouteScopeKey;
import io.fluxora.gateway.route.RouteTargetSelector;
import io.fluxora.gateway.runtime.RuntimeL1Caches;
import io.fluxora.gateway.runtime.RuntimeScopeType;
import io.fluxora.gateway.runtime.RuntimeSnapshot;
import io.fluxora.gateway.runtime.RuntimeSnapshotSource;
import io.vertx.core.Future;
import io.vertx.core.Promise;
import io.vertx.core.json.JsonArray;
import io.vertx.core.json.JsonObject;
import java.time.Duration;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/** Gateway 热路径测试使用内存快照来源，确保无需 PostgreSQL 或真实 Redis。 */
class GatewayRuntimeBehaviorTest {
    private static final String SECRET = "gateway-runtime-test-secret-at-least-32-bytes";

    @Test
    void validApiKeyShouldUseL1AfterFirstSnapshotLoad() throws Exception {
        MemorySource source = new MemorySource();
        String apiKey = "flx_AbCdEf12_0123456789abcdefghijklmnopqrstuv";
        String lookupHash = new ApiKeyLookupHasher(SECRET).hash(apiKey);
        source.put(RuntimeScopeType.AUTH_API_KEY, lookupHash, apiKeySnapshot(lookupHash, true));
        source.put(RuntimeScopeType.AUTH_USER, "7:9", userSnapshot(7, 9, true));
        source.put(RuntimeScopeType.AUTH_TENANT, "7", tenantSnapshot(7, true));

        GatewayAuthenticator authenticator = authenticator(source);
        AuthenticatedPrincipal first = await(authenticator.authenticate(apiKey));
        AuthenticatedPrincipal second = await(authenticator.authenticate(apiKey));

        assertEquals(new AuthenticatedPrincipal(5, 7, 9), first);
        assertEquals(first, second);
        assertEquals(1, source.loads(RuntimeScopeType.AUTH_API_KEY, lookupHash));
        assertEquals(1, source.loads(RuntimeScopeType.AUTH_USER, "7:9"));
        assertEquals(1, source.loads(RuntimeScopeType.AUTH_TENANT, "7"));
    }

    @Test
    void invalidApiKeyShouldEnterNegativeCacheWithoutRepeatedSnapshotRead() {
        MemorySource source = new MemorySource();
        String apiKey = "flx_AbCdEf12_0123456789abcdefghijklmnopqrstuv";
        String lookupHash = new ApiKeyLookupHasher(SECRET).hash(apiKey);
        source.put(RuntimeScopeType.AUTH_API_KEY, lookupHash, apiKeySnapshot(lookupHash, false));
        GatewayAuthenticator authenticator = authenticator(source);

        assertThrows(Exception.class, () -> await(authenticator.authenticate(apiKey)));
        assertThrows(Exception.class, () -> await(authenticator.authenticate(apiKey)));
        assertEquals(1, source.loads(RuntimeScopeType.AUTH_API_KEY, lookupHash));
    }

    @Test
    void disabledUserMustRejectAnOtherwiseValidApiKey() {
        MemorySource source = new MemorySource();
        String apiKey = "flx_AbCdEf12_0123456789abcdefghijklmnopqrstuv";
        String lookupHash = new ApiKeyLookupHasher(SECRET).hash(apiKey);
        source.put(RuntimeScopeType.AUTH_API_KEY, lookupHash, apiKeySnapshot(lookupHash, true));
        source.put(RuntimeScopeType.AUTH_USER, "7:9", userSnapshot(7, 9, false));
        source.put(RuntimeScopeType.AUTH_TENANT, "7", tenantSnapshot(7, true));

        Exception exception = assertThrows(Exception.class, () -> await(authenticator(source).authenticate(apiKey)));

        assertEquals(GatewayFailure.Type.ACCOUNT_UNAVAILABLE, gatewayFailure(exception).type());
    }

    @Test
    void sameModelCodeMustResolveToDifferentTenantRouteScopes() throws Exception {
        MemorySource source = new MemorySource();
        String modelCode = "gpt-demo";
        String a = RouteScopeKey.of(101, "OPENAI", modelCode);
        String b = RouteScopeKey.of(202, "OPENAI", modelCode);
        source.put(RuntimeScopeType.TENANT_MODEL_ROUTE, a, routeSnapshot(101, modelCode, 1001));
        source.put(RuntimeScopeType.TENANT_MODEL_ROUTE, b, routeSnapshot(202, modelCode, 2001));
        GatewayRouteResolver resolver = routeResolver(source);

        assertEquals(1001L, await(resolver.resolve(101, "OPENAI", modelCode, false)).routeTargetId());
        assertEquals(2001L, await(resolver.resolve(202, "OPENAI", modelCode, false)).routeTargetId());
    }

    @Test
    void selectorMustAlwaysPreferLowestPriorityGroup() {
        JsonArray targets = new JsonArray()
                .add(target(8, 20, 1))
                .add(target(9, 10, 100));
        RouteTargetSelector selector = new RouteTargetSelector();
        for (int index = 0; index < 200; index++) {
            assertEquals(9L, selector.select(targets, "OPENAI").routeTargetId());
        }
    }

    @Test
    void disabledCredentialPoolMustMakeRouteUnavailable() {
        MemorySource source = new MemorySource();
        String scope = RouteScopeKey.of(101, "OPENAI", "gpt-demo");
        JsonObject route = routeSnapshot(101, "gpt-demo", 1001);
        route.getJsonArray("targets").getJsonObject(0).put("hasUsableCredential", false);
        source.put(RuntimeScopeType.TENANT_MODEL_ROUTE, scope, route);

        Exception exception = assertThrows(Exception.class,
                () -> await(routeResolver(source).resolve(101, "OPENAI", "gpt-demo", false)));

        assertEquals(GatewayFailure.Type.MODEL_UNAVAILABLE, gatewayFailure(exception).type());
    }

    @Test
    void concurrentScopeMissesMustShareOneSnapshotRead() throws Exception {
        DeferredSource source = new DeferredSource();
        GatewayMetrics metrics = new GatewayMetrics();
        RuntimeL1Caches caches = new RuntimeL1Caches(source, config(), metrics);
        String lookupHash = "a".repeat(64);
        Future<RuntimeSnapshot> first = caches.apiKey(lookupHash);
        Future<RuntimeSnapshot> second = caches.apiKey(lookupHash);

        assertEquals(1, source.loads());
        source.complete(new RuntimeSnapshot(RuntimeScopeType.AUTH_API_KEY, lookupHash, 1L,
                apiKeySnapshot(lookupHash, true)));
        assertEquals(lookupHash, await(first).scopeKey());
        assertEquals(lookupHash, await(second).scopeKey());
    }

    private GatewayAuthenticator authenticator(MemorySource source) {
        GatewayMetrics metrics = new GatewayMetrics();
        RuntimeL1Caches caches = new RuntimeL1Caches(source, config(), metrics);
        return new GatewayAuthenticator(new ApiKeyLookupHasher(SECRET), caches, metrics);
    }

    private GatewayRouteResolver routeResolver(MemorySource source) {
        RuntimeL1Caches caches = new RuntimeL1Caches(source, config(), new GatewayMetrics());
        return new GatewayRouteResolver(caches, new RouteTargetSelector());
    }

    private GatewayRuntimeConfig config() {
        return new GatewayRuntimeConfig("redis://unused", "unused", SECRET,
                Duration.ofSeconds(5), Duration.ofSeconds(5), Duration.ofSeconds(5), Duration.ofSeconds(5),
                Duration.ofSeconds(2), 100);
    }

    private JsonObject apiKeySnapshot(String lookupHash, boolean enabled) {
        return new JsonObject().put("schemaVersion", 1).put("runtimeVersion", 1).put("generatedAt", "2026-01-01T00:00:00Z")
                .put("lookupHash", lookupHash).put("lookupHashVersion", 1).put("apiKeyId", 5L)
                .put("tenantId", 7L).put("userId", 9L).put("enabled", enabled)
                .put("apiKeyStatus", enabled ? "ENABLED" : "DISABLED");
    }

    private JsonObject userSnapshot(long tenantId, long userId, boolean enabled) {
        return new JsonObject().put("schemaVersion", 1).put("runtimeVersion", 1).put("generatedAt", "2026-01-01T00:00:00Z")
                .put("tenantId", tenantId).put("userId", userId).put("enabled", enabled)
                .put("userStatus", enabled ? "ENABLED" : "DISABLED");
    }

    private JsonObject tenantSnapshot(long tenantId, boolean enabled) {
        return new JsonObject().put("schemaVersion", 1).put("runtimeVersion", 1).put("generatedAt", "2026-01-01T00:00:00Z")
                .put("tenantId", tenantId).put("enabled", enabled)
                .put("tenantStatus", enabled ? "ENABLED" : "DISABLED");
    }

    private JsonObject routeSnapshot(long tenantId, String modelCode, long targetId) {
        return new JsonObject().put("schemaVersion", 1).put("runtimeVersion", 1).put("generatedAt", "2026-01-01T00:00:00Z")
                .put("tenantId", tenantId).put("tenantModelCode", modelCode).put("inboundProtocol", "OPENAI")
                .put("tenantModelEnabled", true).put("tenantModelStatus", "ENABLED")
                .put("routeEnabled", true).put("routeStatus", "ENABLED").put("priceAvailable", true)
                .put("priceEffectiveAt", "2025-01-01T00:00:00Z")
                .put("targets", new JsonArray().add(target(targetId, 1, 100)));
    }

    private JsonObject target(long id, int priority, int weight) {
        return new JsonObject().put("routeTargetId", id).put("providerChannelId", id + 10)
                .put("providerChannelModelId", id + 20).put("priority", priority).put("weight", weight)
                .put("targetStatus", "ENABLED").put("mappingStatus", "ENABLED")
                .put("candidateStatus", "ENABLED").put("channelStatus", "ENABLED")
                .put("hasUsableCredential", true).put("outboundProtocol", "OPENAI")
                .put("upstreamModelId", "internal-only");
    }

    private static <T> T await(Future<T> future) throws Exception {
        return future.toCompletionStage().toCompletableFuture().get(3, TimeUnit.SECONDS);
    }

    private static GatewayFailure gatewayFailure(Exception exception) {
        Throwable cursor = exception;
        while (cursor.getCause() != null && !(cursor instanceof GatewayFailure)) {
            cursor = cursor.getCause();
        }
        if (cursor instanceof GatewayFailure failure) return failure;
        throw new AssertionError("未获得 GatewayFailure", exception);
    }

    private static final class MemorySource implements RuntimeSnapshotSource {
        private final Map<String, RuntimeSnapshot> snapshots = new ConcurrentHashMap<>();
        private final Map<String, AtomicInteger> readCounts = new ConcurrentHashMap<>();

        void put(RuntimeScopeType type, String key, JsonObject payload) {
            snapshots.put(key(type, key), new RuntimeSnapshot(type, key, 1L, payload));
        }

        int loads(RuntimeScopeType type, String key) { return readCounts.getOrDefault(key(type, key), new AtomicInteger()).get(); }

        @Override
        public Future<RuntimeSnapshot> load(RuntimeScopeType type, String key) {
            String compound = key(type, key);
            readCounts.computeIfAbsent(compound, ignored -> new AtomicInteger()).incrementAndGet();
            RuntimeSnapshot snapshot = snapshots.get(compound);
            return snapshot == null ? Future.failedFuture(GatewayFailure.runtimeUnavailable()) : Future.succeededFuture(snapshot);
        }

        @Override
        public Future<Long> manifestVersion(RuntimeScopeType type, String key) {
            RuntimeSnapshot snapshot = snapshots.get(key(type, key));
            return snapshot == null ? Future.failedFuture(GatewayFailure.runtimeUnavailable())
                    : Future.succeededFuture(snapshot.runtimeVersion());
        }

        private static String key(RuntimeScopeType type, String scope) { return type + "|" + scope; }
    }

    /** 延迟完成的快照来源用于验证 Caffeine AsyncCache 将同 Scope 未命中合并为一次读取。 */
    private static final class DeferredSource implements RuntimeSnapshotSource {
        private final Promise<RuntimeSnapshot> promise = Promise.promise();
        private final AtomicInteger reads = new AtomicInteger();

        @Override
        public Future<RuntimeSnapshot> load(RuntimeScopeType type, String key) {
            reads.incrementAndGet();
            return promise.future();
        }

        @Override
        public Future<Long> manifestVersion(RuntimeScopeType type, String key) {
            return Future.succeededFuture(1L);
        }

        int loads() { return reads.get(); }

        void complete(RuntimeSnapshot snapshot) { promise.complete(snapshot); }
    }
}
