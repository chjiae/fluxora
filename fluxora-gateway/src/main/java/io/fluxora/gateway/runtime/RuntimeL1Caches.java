package io.fluxora.gateway.runtime;

import com.github.benmanes.caffeine.cache.AsyncCache;
import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import io.fluxora.gateway.GatewayMetrics;
import io.fluxora.gateway.GatewayRuntimeConfig;
import io.vertx.core.Future;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicLong;
import java.util.stream.Stream;

/**
 * Gateway L1：四类快照独立硬 TTL，未命中使用 Caffeine AsyncCache 合并 in-flight Redis 读取。
 * 所有键均为 HMAC 摘要或内部 Scope Key，绝不缓存 API Key 明文。
 */
public final class RuntimeL1Caches {
    private final RuntimeSnapshotSource source;
    private final GatewayMetrics metrics;
    private final AsyncCache<String, RuntimeSnapshot> apiKeys;
    private final AsyncCache<String, RuntimeSnapshot> users;
    private final AsyncCache<String, RuntimeSnapshot> tenants;
    private final AsyncCache<String, RuntimeSnapshot> routes;
    private final AsyncCache<String, RuntimeSnapshot> credentials;
    private final Cache<String, Boolean> invalidApiKeys;

    public RuntimeL1Caches(RuntimeSnapshotSource source, GatewayRuntimeConfig config, GatewayMetrics metrics) {
        this.source = source;
        this.metrics = metrics;
        this.apiKeys = asyncCache(config.apiKeyTtl(), config.maxCacheEntries());
        this.users = asyncCache(config.userTtl(), config.maxCacheEntries());
        this.tenants = asyncCache(config.tenantTtl(), config.maxCacheEntries());
        this.routes = asyncCache(config.routeTtl(), config.maxCacheEntries());
        this.credentials = asyncCache(config.credentialTtl(), config.maxCacheEntries());
        this.invalidApiKeys = Caffeine.<String, Boolean>newBuilder()
                .maximumSize(config.maxCacheEntries()).expireAfterWrite(config.invalidApiKeyTtl()).build();
    }

    public Future<RuntimeSnapshot> apiKey(String lookupHash) {
        return load(apiKeys, RuntimeScopeType.AUTH_API_KEY, lookupHash, metrics.apiKeyL1Hit);
    }

    public Future<RuntimeSnapshot> user(long tenantId, long userId) {
        return load(users, RuntimeScopeType.AUTH_USER, tenantId + ":" + userId, metrics.userL1Hit);
    }

    public Future<RuntimeSnapshot> tenant(long tenantId) {
        return load(tenants, RuntimeScopeType.AUTH_TENANT, Long.toString(tenantId), metrics.tenantL1Hit);
    }

    public Future<RuntimeSnapshot> route(String scopeKey) {
        return load(routes, RuntimeScopeType.TENANT_MODEL_ROUTE, scopeKey, metrics.routeL1Hit);
    }

    /** 只缓存 Redis 敏感快照密文；解密后的 String 绝不写入 Caffeine。 */
    public Future<RuntimeSnapshot> credential(long tenantId, long credentialId) {
        return load(credentials, RuntimeScopeType.UPSTREAM_CREDENTIAL, tenantId + ":" + credentialId,
                metrics.credentialL1Hit);
    }

    public boolean isInvalidApiKey(String lookupHash) {
        boolean cached = invalidApiKeys.getIfPresent(lookupHash) != null;
        if (cached) {
            metrics.invalidKeyNegativeHit.incrementAndGet();
        }
        return cached;
    }

    public void rememberInvalidApiKey(String lookupHash) {
        invalidApiKeys.put(lookupHash, Boolean.TRUE);
    }

    public RuntimeSnapshot peek(RuntimeScopeType type, String scopeKey) {
        CompletableFuture<RuntimeSnapshot> future = cache(type).getIfPresent(scopeKey);
        return future != null && future.isDone() && !future.isCompletedExceptionally() ? future.getNow(null) : null;
    }

    public void invalidate(RuntimeScopeType type, String scopeKey) {
        cache(type).synchronous().invalidate(scopeKey);
        if (type == RuntimeScopeType.AUTH_API_KEY) {
            invalidApiKeys.invalidate(scopeKey);
        }
    }

    public void invalidateAll() {
        apiKeys.synchronous().invalidateAll();
        users.synchronous().invalidateAll();
        tenants.synchronous().invalidateAll();
        routes.synchronous().invalidateAll();
        credentials.synchronous().invalidateAll();
        invalidApiKeys.invalidateAll();
    }

    /**
     * 对已缓存热点 Scope 做受限 Manifest 版本核对；失败时保留未过硬 TTL 的本地值，
     * 下一次过期读取仍会失败关闭，避免 Redis 故障时把所有热请求同步打到 Redis。
     */
    public void verifyHotManifestVersions(int limit) {
        Stream.concat(Stream.concat(apiKeys.synchronous().asMap().values().stream(), users.synchronous().asMap().values().stream()),
                        Stream.concat(tenants.synchronous().asMap().values().stream(),
                                Stream.concat(routes.synchronous().asMap().values().stream(), credentials.synchronous().asMap().values().stream())))
                .limit(limit)
                .forEach(snapshot -> {
                    metrics.redisSnapshotRead.incrementAndGet();
                    source.manifestVersion(snapshot.scopeType(), snapshot.scopeKey()).onSuccess(version -> {
                        if (version > snapshot.runtimeVersion()) {
                            invalidate(snapshot.scopeType(), snapshot.scopeKey());
                        }
                    }).onFailure(ignored -> metrics.redisReadFailure.incrementAndGet());
                });
    }

    private Future<RuntimeSnapshot> load(AsyncCache<String, RuntimeSnapshot> cache, RuntimeScopeType type,
                                         String scopeKey, AtomicLong hitMetric) {
        CompletableFuture<RuntimeSnapshot> cached = cache.getIfPresent(scopeKey);
        if (cached != null) {
            hitMetric.incrementAndGet();
            return Future.fromCompletionStage(cached);
        }
        metrics.singleflightJoin.incrementAndGet();
        CompletableFuture<RuntimeSnapshot> future = cache.get(scopeKey, (key, executor) -> {
            metrics.redisSnapshotRead.incrementAndGet();
            return source.load(type, key).toCompletionStage().toCompletableFuture()
                    .whenComplete((value, error) -> {
                        if (error != null) {
                            metrics.redisReadFailure.incrementAndGet();
                        }
                    });
        });
        return Future.fromCompletionStage(future);
    }

    private AsyncCache<String, RuntimeSnapshot> cache(RuntimeScopeType type) {
        return switch (type) {
            case AUTH_API_KEY -> apiKeys;
            case AUTH_USER -> users;
            case AUTH_TENANT -> tenants;
            case TENANT_MODEL_ROUTE -> routes;
            case UPSTREAM_CREDENTIAL -> credentials;
        };
    }

    private static AsyncCache<String, RuntimeSnapshot> asyncCache(java.time.Duration ttl, int maxEntries) {
        return Caffeine.<String, RuntimeSnapshot>newBuilder().maximumSize(maxEntries).expireAfterWrite(ttl).recordStats().buildAsync();
    }
}
