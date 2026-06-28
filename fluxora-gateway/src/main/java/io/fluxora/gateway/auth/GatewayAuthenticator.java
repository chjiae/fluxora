package io.fluxora.gateway.auth;

import io.fluxora.gateway.GatewayFailure;
import io.fluxora.gateway.GatewayMetrics;
import io.fluxora.gateway.runtime.RuntimeL1Caches;
import io.vertx.core.Future;
import io.vertx.core.json.JsonObject;
import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.Optional;

/**
 * API Key → 用户 → 租户的严格鉴权链路。
 *
 * <p>Gateway 只读取运行时快照中的最小准入字段，不同步查询 Platform 或数据库余额。
 * 用户余额是否允许新请求由 Platform 投影为 billingEligibility；这里仅按快照结果在上游派发前失败关闭。</p>
 */
public final class GatewayAuthenticator {
    private final ApiKeyLookupHasher hasher;
    private final RuntimeL1Caches caches;
    private final GatewayMetrics metrics;

    public GatewayAuthenticator(ApiKeyLookupHasher hasher, RuntimeL1Caches caches, GatewayMetrics metrics) {
        this.hasher = hasher;
        this.caches = caches;
        this.metrics = metrics;
    }

    public Future<AuthenticatedPrincipal> authenticate(String rawApiKey) {
        Optional<String> canonical = ApiKeyCanonicalizer.canonicalize(rawApiKey);
        if (canonical.isEmpty()) {
            return Future.failedFuture(GatewayFailure.invalidApiKey());
        }
        String lookupHash = hasher.hash(canonical.get());
        if (caches.isInvalidApiKey(lookupHash)) {
            return Future.failedFuture(GatewayFailure.invalidApiKey());
        }
        return caches.apiKey(lookupHash).compose(apiKey -> {
            if (!activeApiKey(apiKey.payload(), lookupHash)) {
                caches.rememberInvalidApiKey(lookupHash);
                return Future.failedFuture(GatewayFailure.invalidApiKey());
            }
            long tenantId = requiredLong(apiKey.payload(), "tenantId");
            long userId = requiredLong(apiKey.payload(), "userId");
            long apiKeyId = requiredLong(apiKey.payload(), "apiKeyId");
            return caches.user(tenantId, userId).compose(user -> {
                if (!activeUserStatus(user.payload(), tenantId, userId)) {
                    return Future.failedFuture(GatewayFailure.accountUnavailable());
                }
                GatewayFailure billingFailure = billingEligibilityFailure(user.payload());
                if (billingFailure != null) {
                    return Future.failedFuture(billingFailure);
                }
                return caches.tenant(tenantId).compose(tenant -> {
                    if (!activeTenant(tenant.payload(), tenantId)) {
                        return Future.failedFuture(GatewayFailure.accountUnavailable());
                    }
                    return Future.succeededFuture(new AuthenticatedPrincipal(apiKeyId, tenantId, userId));
                });
            });
        }).recover(error -> {
            if (error instanceof GatewayFailure failure) {
                if (failure.type() == GatewayFailure.Type.RUNTIME_UNAVAILABLE) metrics.failClosed.incrementAndGet();
                return Future.failedFuture(failure);
            }
            metrics.failClosed.incrementAndGet();
            return Future.failedFuture(GatewayFailure.runtimeUnavailable());
        });
    }

    private boolean activeApiKey(JsonObject snapshot, String lookupHash) {
        return snapshot.getInteger("lookupHashVersion", 0) == 1
                && lookupHash.equals(snapshot.getString("lookupHash"))
                && snapshot.getBoolean("enabled", false)
                && "ENABLED".equals(snapshot.getString("apiKeyStatus"))
                && notExpired(snapshot.getString("apiKeyExpiresAt"));
    }

    private boolean activeUserStatus(JsonObject snapshot, long tenantId, long userId) {
        return snapshot.getLong("tenantId", -1L) == tenantId && snapshot.getLong("userId", -1L) == userId
                && snapshot.getBoolean("enabled", false) && "ENABLED".equals(snapshot.getString("userStatus"));
    }

    /**
     * 余额准入只使用 Platform 投影的 billingEligibility。
     * ALLOWED 表示余额 > 0；余额变为 0 或负数后，新快照会拒绝后续请求，但不会中断已放行请求。
     */
    private GatewayFailure billingEligibilityFailure(JsonObject snapshot) {
        String eligibility = snapshot.getString("billingEligibility");
        if ("ALLOWED".equals(eligibility)) {
            return null;
        }
        if ("BLOCKED_INSUFFICIENT_BALANCE".equals(eligibility)) {
            return GatewayFailure.insufficientBalance();
        }
        if ("BLOCKED_USER_STATUS".equals(eligibility) || "BLOCKED_TENANT_STATUS".equals(eligibility)) {
            return GatewayFailure.accountUnavailable();
        }
        throw GatewayFailure.runtimeUnavailable();
    }

    private boolean activeTenant(JsonObject snapshot, long tenantId) {
        return snapshot.getLong("tenantId", -1L) == tenantId && snapshot.getBoolean("enabled", false)
                && "ENABLED".equals(snapshot.getString("tenantStatus"))
                && notExpired(snapshot.getString("tenantExpiresAt"));
    }

    private boolean notExpired(String value) {
        if (value == null) return true;
        try {
            return Instant.parse(value).isAfter(Instant.now());
        } catch (DateTimeParseException error) {
            throw GatewayFailure.runtimeUnavailable();
        }
    }

    private long requiredLong(JsonObject source, String field) {
        Long value = source.getLong(field);
        if (value == null || value < 1L) throw GatewayFailure.runtimeUnavailable();
        return value;
    }
}
