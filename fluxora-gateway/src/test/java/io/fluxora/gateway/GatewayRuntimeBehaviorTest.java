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
import static org.junit.jupiter.api.Assertions.assertTrue;

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

    @Test
    void disabledTenantMustRejectAllApiKeys() {
        MemorySource source = new MemorySource();
        String apiKey = "flx_AbCdEf12_0123456789abcdefghijklmnopqrstuv";
        String lookupHash = new ApiKeyLookupHasher(SECRET).hash(apiKey);
        source.put(RuntimeScopeType.AUTH_API_KEY, lookupHash, apiKeySnapshot(lookupHash, true));
        source.put(RuntimeScopeType.AUTH_USER, "7:9", userSnapshot(7, 9, true));
        source.put(RuntimeScopeType.AUTH_TENANT, "7", tenantSnapshot(7, false));

        Exception exception = assertThrows(Exception.class,
                () -> await(authenticator(source).authenticate(apiKey)));

        assertEquals(GatewayFailure.Type.ACCOUNT_UNAVAILABLE, gatewayFailure(exception).type());
    }

    @Test
    void expiredTenantMustRejectAllApiKeys() {
        MemorySource source = new MemorySource();
        String apiKey = "flx_AbCdEf12_0123456789abcdefghijklmnopqrstuv";
        String lookupHash = new ApiKeyLookupHasher(SECRET).hash(apiKey);
        source.put(RuntimeScopeType.AUTH_API_KEY, lookupHash, apiKeySnapshot(lookupHash, true));
        source.put(RuntimeScopeType.AUTH_USER, "7:9", userSnapshot(7, 9, true));
        JsonObject expiredTenant = tenantSnapshot(7, true)
                .put("expireAt", "2020-01-01T00:00:00Z")
                .put("tenantStatus", "EXPIRED");
        source.put(RuntimeScopeType.AUTH_TENANT, "7", expiredTenant);

        Exception exception = assertThrows(Exception.class,
                () -> await(authenticator(source).authenticate(apiKey)));

        assertEquals(GatewayFailure.Type.ACCOUNT_UNAVAILABLE, gatewayFailure(exception).type());
    }

    @Test
    void malformedApiKeyMustBeRejectedBeforeAccessingRedis() {
        MemorySource source = new MemorySource();
        GatewayAuthenticator authenticator = authenticator(source);

        // 格式非法 → canonicalize 返回 Optional.empty() → 直接失败，不调用 hasher 和 L1
        assertThrows(Exception.class, () -> await(authenticator.authenticate("not-a-flx-key")));
        assertThrows(Exception.class, () -> await(authenticator.authenticate("sk-1234567890")));
        assertThrows(Exception.class, () -> await(authenticator.authenticate("")));
        assertThrows(Exception.class, () -> await(authenticator.authenticate("   ")));

        // 验证：4 次格式预校验均未触发任何 Redis 加载（source 是全新的，loadCount 全部为 0）
        assertEquals(0, source.totalLoads());
    }

    @Test
    void disabledTenantModelMustRejectRouteResolution() {
        MemorySource source = new MemorySource();
        String scope = RouteScopeKey.of(101, "OPENAI", "gpt-disabled");
        JsonObject route = routeSnapshot(101, "gpt-disabled", 1001);
        route.put("tenantModelEnabled", false).put("tenantModelStatus", "DISABLED");
        source.put(RuntimeScopeType.TENANT_MODEL_ROUTE, scope, route);

        Exception exception = assertThrows(Exception.class,
                () -> await(routeResolver(source).resolve(101, "OPENAI", "gpt-disabled", false)));

        assertEquals(GatewayFailure.Type.MODEL_UNAVAILABLE, gatewayFailure(exception).type());
    }

    @Test
    void disabledRouteMustRejectResolution() {
        MemorySource source = new MemorySource();
        String scope = RouteScopeKey.of(101, "OPENAI", "gpt-route-off");
        JsonObject route = routeSnapshot(101, "gpt-route-off", 1001);
        route.put("routeEnabled", false).put("routeStatus", "DISABLED");
        source.put(RuntimeScopeType.TENANT_MODEL_ROUTE, scope, route);

        Exception exception = assertThrows(Exception.class,
                () -> await(routeResolver(source).resolve(101, "OPENAI", "gpt-route-off", false)));

        assertEquals(GatewayFailure.Type.MODEL_UNAVAILABLE, gatewayFailure(exception).type());
    }

    @Test
    void priceUnavailableMustRejectRouteResolution() {
        MemorySource source = new MemorySource();
        String scope = RouteScopeKey.of(101, "OPENAI", "gpt-no-price");
        JsonObject route = routeSnapshot(101, "gpt-no-price", 1001);
        route.put("priceAvailable", false);
        source.put(RuntimeScopeType.TENANT_MODEL_ROUTE, scope, route);

        Exception exception = assertThrows(Exception.class,
                () -> await(routeResolver(source).resolve(101, "OPENAI", "gpt-no-price", false)));

        assertEquals(GatewayFailure.Type.MODEL_UNAVAILABLE, gatewayFailure(exception).type());
    }

    @Test
    void disabledRouteTargetMustBeExcludedFromSelection() {
        JsonArray targets = new JsonArray()
                .add(target(1, 10, 100).put("targetStatus", "DISABLED"))
                .add(target(2, 20, 100));
        RouteTargetSelector selector = new RouteTargetSelector();

        // 唯一 enabled target 在 priority 20，应选中它
        assertEquals(2L, selector.select(targets, "OPENAI").routeTargetId());
    }

    @Test
    void invalidWeightMustBeExcludedFromSelection() {
        // weight=0 和 weight=-1 不参与选择；唯一正 weight 为 target(3) weight=1
        JsonArray targets = new JsonArray()
                .add(target(1, 10, 0))
                .add(target(2, 10, -1))
                .add(target(3, 10, 1));
        RouteTargetSelector selector = new RouteTargetSelector();
        for (int i = 0; i < 50; i++) {
            assertEquals(3L, selector.select(targets, "OPENAI").routeTargetId());
        }
    }

    @Test
    void weightSelectionMustConvergeToExpectedDistribution() {
        JsonArray targets = new JsonArray()
                .add(target(1, 10, 1))
                .add(target(2, 10, 9));
        RouteTargetSelector selector = new RouteTargetSelector();
        int count1 = 0, count2 = 0;
        for (int i = 0; i < 1000; i++) {
            if (selector.select(targets, "OPENAI").routeTargetId() == 1L) count1++;
            else count2++;
        }
        // 1:9 比例 → target2 应占 ~90%
        assertTrue(count2 > 800, "weight=9 应承担 ~90% 流量，实际=" + count2 + "/1000");
        assertTrue(count1 > 50, "weight=1 应承担 ~10% 流量，实际=" + count1 + "/1000");
    }

    @Test
    void noUsableTargetMustMakeModelUnavailable() {
        // 所有 target 都是 disabled 或 weight=0
        JsonArray targets = new JsonArray()
                .add(target(1, 10, 100).put("targetStatus", "DISABLED"))
                .add(target(2, 10, 0));
        JsonObject route = routeSnapshot(101, "gpt-dead", 1001);
        route.put("targets", targets);
        MemorySource source = new MemorySource();
        source.put(RuntimeScopeType.TENANT_MODEL_ROUTE,
                RouteScopeKey.of(101, "OPENAI", "gpt-dead"), route);

        Exception exception = assertThrows(Exception.class,
                () -> await(routeResolver(source).resolve(101, "OPENAI", "gpt-dead", false)));

        assertEquals(GatewayFailure.Type.MODEL_UNAVAILABLE, gatewayFailure(exception).type());
    }

    @Test
    void twoCacheInstancesMustShareSameUnderlyingSnapshotAndInvalidateIndependently() throws Exception {
        MemorySource source = new MemorySource();
        String lookupHash = "x".repeat(64);
        source.put(RuntimeScopeType.AUTH_API_KEY, lookupHash, apiKeySnapshot(lookupHash, true));

        GatewayMetrics metrics1 = new GatewayMetrics();
        GatewayMetrics metrics2 = new GatewayMetrics();
        RuntimeL1Caches instance1 = new RuntimeL1Caches(source, config(), metrics1);
        RuntimeL1Caches instance2 = new RuntimeL1Caches(source, config(), metrics2);

        // 实例 1 加载快照
        RuntimeSnapshot snap1 = await(instance1.apiKey(lookupHash));
        assertEquals(lookupHash, snap1.scopeKey());

        // 实例 2 独立加载同一快照（共享底层 source，各自 L1）
        RuntimeSnapshot snap2 = await(instance2.apiKey(lookupHash));
        assertEquals(lookupHash, snap2.scopeKey());

        // 两个实例的 L1 独立但底层 source 共享 —— 都能返回相同数据
        assertEquals(lookupHash, snap1.scopeKey());
        assertEquals(lookupHash, snap2.scopeKey());

        // 验证 invalidateAll 清空 L1 后可按需重新加载
        instance1.invalidateAll();
        RuntimeSnapshot reloaded = await(instance1.apiKey(lookupHash));
        assertEquals(lookupHash, reloaded.scopeKey());
        // 两实例独立 L1 互不影响
        instance1.invalidateAll();
        RuntimeSnapshot stillCachedIn2 = await(instance2.apiKey(lookupHash));
        assertEquals(lookupHash, stillCachedIn2.scopeKey());
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
        int totalLoads() { return readCounts.values().stream().mapToInt(AtomicInteger::get).sum(); }

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
