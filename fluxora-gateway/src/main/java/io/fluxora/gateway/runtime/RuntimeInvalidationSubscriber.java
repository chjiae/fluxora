package io.fluxora.gateway.runtime;

import io.fluxora.gateway.GatewayMetrics;
import io.vertx.core.Vertx;
import io.vertx.core.json.JsonObject;
import io.vertx.redis.client.Command;
import io.vertx.redis.client.Redis;
import io.vertx.redis.client.RedisConnection;
import io.vertx.redis.client.Request;
import io.vertx.redis.client.Response;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Pub/Sub 仅用来低延迟删除本机 L1；断线后的正确性由硬 TTL 与 Redis Manifest 保证。
 * 重连订阅成功时清空所有本机快照，避免漏消息留下旧版本。
 */
public final class RuntimeInvalidationSubscriber {
    private final Vertx vertx;
    private final Redis redis;
    private final String channel;
    private final RuntimeL1Caches caches;
    private final GatewayMetrics metrics;
    private final AtomicBoolean closed = new AtomicBoolean();
    private boolean reconnectScheduled;
    private long retryDelayMs = 250L;
    private RedisConnection connection;

    public RuntimeInvalidationSubscriber(Vertx vertx, Redis redis, String channel,
                                         RuntimeL1Caches caches, GatewayMetrics metrics) {
        this.vertx = vertx;
        this.redis = redis;
        this.channel = channel;
        this.caches = caches;
        this.metrics = metrics;
    }

    public void start() {
        connect();
    }

    public boolean healthy() {
        return connection != null && !reconnectScheduled;
    }

    public void close() {
        closed.set(true);
        if (connection != null) connection.close();
    }

    private void connect() {
        if (closed.get()) return;
        redis.connect().onSuccess(conn -> {
            connection = conn;
            conn.exceptionHandler(ignored -> disconnected());
            conn.endHandler(ignored -> disconnected());
            conn.handler(this::handleMessage);
            conn.send(Request.cmd(Command.SUBSCRIBE).arg(channel)).onSuccess(ignored -> {
                retryDelayMs = 250L;
                reconnectScheduled = false;
                // Pub/Sub 可能漏消息；成功重连后一律按需从 Redis 重新加载。
                caches.invalidateAll();
            }).onFailure(ignored -> disconnected());
        }).onFailure(ignored -> disconnected());
    }

    private void disconnected() {
        connection = null;
        if (closed.get() || reconnectScheduled) return;
        reconnectScheduled = true;
        long delay = retryDelayMs;
        retryDelayMs = Math.min(10_000L, retryDelayMs * 2L);
        vertx.setTimer(delay, ignored -> {
            reconnectScheduled = false;
            connect();
        });
    }

    private void handleMessage(Response response) {
        try {
            if (response == null || response.size() < 3 || !"message".equals(response.get(0).toString())) return;
            JsonObject event = new JsonObject(response.get(2).toString());
            RuntimeScopeType type = RuntimeScopeType.valueOf(event.getString("scopeType"));
            String scopeKey = event.getString("scopeKey");
            long version = event.getLong("newRuntimeVersion", -1L);
            if (scopeKey == null || version < 1L) return;
            metrics.invalidationReceived.incrementAndGet();
            RuntimeSnapshot local = caches.peek(type, scopeKey);
            if (local == null) return;
            if (local.runtimeVersion() < version) {
                caches.invalidate(type, scopeKey);
            } else {
                metrics.invalidationIgnoredOld.incrementAndGet();
            }
        } catch (RuntimeException ignored) {
            // 不可信通知只可被忽略；后续硬 TTL 与 Manifest 检查仍会收敛到正确版本。
        }
    }
}
