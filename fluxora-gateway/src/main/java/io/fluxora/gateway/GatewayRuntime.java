package io.fluxora.gateway;

import io.fluxora.gateway.auth.ApiKeyLookupHasher;
import io.fluxora.gateway.billing.PlatformBillingClient;
import io.fluxora.gateway.auth.GatewayAuthenticator;
import io.fluxora.gateway.credential.RuntimeCredentialResolver;
import io.fluxora.gateway.observability.RelayEventPublisher;
import io.fluxora.gateway.model.GatewayModelCatalog;
import io.fluxora.gateway.route.GatewayRouteResolver;
import io.fluxora.gateway.route.RouteTargetSelector;
import io.fluxora.gateway.runtime.RedisRuntimeSnapshotSource;
import io.fluxora.gateway.runtime.RuntimeInvalidationSubscriber;
import io.fluxora.gateway.runtime.RuntimeL1Caches;
import io.vertx.core.Future;
import io.vertx.core.Vertx;
import io.vertx.redis.client.Redis;
import io.vertx.redis.client.RedisOptions;

/** Gateway 运行时组合根：只装配 Redis、L1、HMAC 与内存路由，不含 JDBC、Platform HTTP 或上游凭证能力。 */
public final class GatewayRuntime {
    private final Vertx vertx;
    private final GatewayRuntimeConfig config;
    private final Redis redis;
    private final RuntimeInvalidationSubscriber subscriber;
    private final GatewayAuthenticator authenticator;
    private final GatewayRouteResolver routeResolver;
    private final GatewayModelCatalog modelCatalog;
    private final PlatformBillingClient billingClient;
    private final RuntimeCredentialResolver credentialResolver;
    private final GatewayMetrics metrics;
    private final RuntimeL1Caches caches;
    /** 中继审计只写 Redis Stream；该对象不接触 JDBC 或 Platform HTTP。 */
    private final RelayEventPublisher relayEventPublisher;
    /** 受限热点版本核对定时器；关闭 Gateway 时必须取消，避免测试/优雅退出后继续访问 Redis。 */
    private long manifestVerificationTimerId = -1L;
    private long relayEventRetryTimerId = -1L;

    public GatewayRuntime(Vertx vertx, GatewayRuntimeConfig config) {
        this.vertx = vertx;
        this.config = config;
        this.redis = Redis.createClient(vertx, new RedisOptions().setConnectionString(config.redisConnectionString()));
        this.metrics = new GatewayMetrics();
        this.caches = new RuntimeL1Caches(new RedisRuntimeSnapshotSource(redis), config, metrics);
        this.authenticator = new GatewayAuthenticator(new ApiKeyLookupHasher(config.apiKeyLookupSecret()), caches, metrics);
        this.routeResolver = new GatewayRouteResolver(caches, new RouteTargetSelector());
        this.modelCatalog = new GatewayModelCatalog(caches);
        this.billingClient = new PlatformBillingClient(vertx, config);
        this.credentialResolver = new RuntimeCredentialResolver(caches, config.runtimeCredentialKey());
        this.subscriber = new RuntimeInvalidationSubscriber(vertx, redis, config.invalidationChannel(), caches, metrics);
        this.relayEventPublisher = RelayEventPublisher.forRedis(redis, metrics, config.relayEventStreamKey(),
                config.relayEventStreamMaxLength(), config.relayEventRetryQueueSize(), config.relayEventRetryMaxAttempts());
    }

    public GatewayAuthenticator authenticator() { return authenticator; }
    public GatewayRouteResolver routeResolver() { return routeResolver; }
    public GatewayModelCatalog modelCatalog() { return modelCatalog; }
    public PlatformBillingClient billingClient() { return billingClient; }
    public RuntimeCredentialResolver credentialResolver() { return credentialResolver; }
    public GatewayMetrics metrics() { return metrics; }
    public RelayEventPublisher relayEventPublisher() { return relayEventPublisher; }

    /** Redis 暂不可用时仍启动 HTTP Server 并失败关闭；后台订阅会自动重连。 */
    public void start() {
        subscriber.start();
        // 受限核对只覆盖本机热点缓存，不能替代 Pub/Sub 和硬 TTL，也不会形成逐请求 Redis 读取。
        manifestVerificationTimerId = vertx.setPeriodic(5_000L, ignored -> caches.verifyHotManifestVersions(32));
        relayEventRetryTimerId = vertx.setPeriodic(configuredRetryDelayMillis(), ignored -> relayEventPublisher.retryPending());
    }

    public Future<Void> close() {
        if (manifestVerificationTimerId >= 0L) {
            vertx.cancelTimer(manifestVerificationTimerId);
            manifestVerificationTimerId = -1L;
        }
        if (relayEventRetryTimerId >= 0L) {
            vertx.cancelTimer(relayEventRetryTimerId);
            relayEventRetryTimerId = -1L;
        }
        subscriber.close();
        return redis.close();
    }

    private long configuredRetryDelayMillis() {
        // 低于 100ms 会在 Redis 故障时无意义地占用 Event Loop；配置值仍允许更慢的恢复节奏。
        return Math.max(100L, config.relayEventRetryDelay().toMillis());
    }
}
