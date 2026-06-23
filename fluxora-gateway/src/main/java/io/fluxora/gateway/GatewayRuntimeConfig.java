package io.fluxora.gateway;

import java.time.Duration;

/** Gateway 的环境配置；仅包含 Redis 与 API Key Lookup Secret，绝不包含 PostgreSQL 或上游凭证配置。 */
public record GatewayRuntimeConfig(String redisConnectionString, String invalidationChannel, String apiKeyLookupSecret,
                                   Duration apiKeyTtl, Duration userTtl, Duration tenantTtl, Duration routeTtl,
                                   Duration invalidApiKeyTtl, int maxCacheEntries) {
    public static GatewayRuntimeConfig fromEnvironment() {
        String host = env("REDIS_HOST", "localhost");
        String port = env("REDIS_PORT", "6379");
        String password = System.getenv("REDIS_PASSWORD");
        String connection = password == null || password.isBlank()
                ? "redis://" + host + ":" + port
                : "redis://:" + password + "@" + host + ":" + port;
        String secret = env("APIKEY_LOOKUP_SECRET",
                "fluxora-apikey-lookup-secret-for-local-development-only-please-override");
        return new GatewayRuntimeConfig(connection,
                env("RUNTIME_INVALIDATION_CHANNEL", "fluxora:runtime:v1:invalidation"), secret,
                duration("GATEWAY_API_KEY_CACHE_TTL_MS", 5_000),
                duration("GATEWAY_USER_CACHE_TTL_MS", 5_000),
                duration("GATEWAY_TENANT_CACHE_TTL_MS", 5_000),
                duration("GATEWAY_ROUTE_CACHE_TTL_MS", 12_000),
                duration("GATEWAY_INVALID_KEY_CACHE_TTL_MS", 1_500),
                integer("GATEWAY_RUNTIME_CACHE_MAX_ENTRIES", 20_000));
    }

    private static Duration duration(String key, long defaultMs) {
        return Duration.ofMillis(Long.parseLong(env(key, Long.toString(defaultMs))));
    }

    private static int integer(String key, int defaultValue) {
        return Integer.parseInt(env(key, Integer.toString(defaultValue)));
    }

    private static String env(String key, String defaultValue) {
        String value = System.getenv(key);
        return value == null || value.isBlank() ? defaultValue : value;
    }
}
