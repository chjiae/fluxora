package io.fluxora.gateway.runtime;

import io.fluxora.gateway.GatewayFailure;
import io.vertx.core.Future;
import io.vertx.core.json.JsonObject;
import io.vertx.redis.client.Command;
import io.vertx.redis.client.Redis;
import io.vertx.redis.client.Request;
import io.vertx.redis.client.Response;

/**
 * Redis 运行时快照读取器。Gateway 永远先读 Manifest 再读对应不可变版本快照，
 * 不会访问 PostgreSQL，也不会拼接多个版本的模型、价格与路由数据。
 */
public final class RedisRuntimeSnapshotSource implements RuntimeSnapshotSource {
    private static final int SCHEMA_VERSION = 1;
    private static final String PREFIX = "fluxora:runtime:v1";

    private final Redis redis;

    public RedisRuntimeSnapshotSource(Redis redis) {
        this.redis = redis;
    }

    @Override
    public Future<RuntimeSnapshot> load(RuntimeScopeType scopeType, String scopeKey) {
        return get(manifestKey(scopeType, scopeKey))
                .compose(manifestText -> {
                    JsonObject manifest = parse(manifestText);
                    long version = validatedManifestVersion(manifest);
                    return get(snapshotKey(scopeType, scopeKey, version)).map(snapshotText -> {
                        JsonObject snapshot = parse(snapshotText);
                        if (snapshot.getInteger("schemaVersion", -1) != SCHEMA_VERSION
                                || snapshot.getLong("runtimeVersion", -1L) != version
                                || snapshot.getString("generatedAt") == null) {
                            throw GatewayFailure.runtimeUnavailable();
                        }
                        return new RuntimeSnapshot(scopeType, scopeKey, version, snapshot);
                    });
                });
    }

    @Override
    public Future<Long> manifestVersion(RuntimeScopeType scopeType, String scopeKey) {
        return get(manifestKey(scopeType, scopeKey)).map(text -> validatedManifestVersion(parse(text)));
    }

    private Future<String> get(String key) {
        return redis.send(Request.cmd(Command.GET).arg(key))
                .map(this::requiredText)
                .recover(error -> Future.failedFuture(GatewayFailure.runtimeUnavailable()));
    }

    private String requiredText(Response response) {
        if (response == null) {
            throw GatewayFailure.runtimeUnavailable();
        }
        String value = response.toString();
        if (value == null || value.isBlank()) {
            throw GatewayFailure.runtimeUnavailable();
        }
        return value;
    }

    private JsonObject parse(String text) {
        try {
            return new JsonObject(text);
        } catch (RuntimeException error) {
            throw GatewayFailure.runtimeUnavailable();
        }
    }

    private long validatedManifestVersion(JsonObject manifest) {
        long version = manifest.getLong("activeRuntimeVersion", -1L);
        if (manifest.getInteger("schemaVersion", -1) != SCHEMA_VERSION || version < 1L) {
            throw GatewayFailure.runtimeUnavailable();
        }
        return version;
    }

    public static String snapshotKey(RuntimeScopeType type, String scopeKey, long version) {
        return PREFIX + ":snapshot:" + type.name() + ":" + scopeKey + ":v:" + version;
    }

    public static String manifestKey(RuntimeScopeType type, String scopeKey) {
        return PREFIX + ":manifest:" + type.name() + ":" + scopeKey;
    }
}
