package io.fluxora.gateway.relay.scheduling;

import io.fluxora.gateway.GatewayRuntimeConfig;
import io.vertx.core.Future;
import io.vertx.redis.client.Command;
import io.vertx.redis.client.Redis;
import io.vertx.redis.client.Request;
import io.vertx.redis.client.Response;
import java.time.Instant;
import java.util.UUID;

/**
 * Redis 原子调度租约。
 *
 * Lua 脚本在一次 Redis 命令内完成：清理过期 lease、读取资源活跃数、检查硬并发上限、写入
 * Channel / quotaScope / Credential 三类 ZSET。Gateway 崩溃后容量通过 TTL 和后续清理自然恢复。
 */
public final class RedisDispatchLeaseManager implements DispatchLeaseManager {
    private static final String ACQUIRE_SCRIPT = """
            local now = tonumber(ARGV[1])
            local expire = tonumber(ARGV[2])
            local leaseId = ARGV[3]
            local maxCredential = tonumber(ARGV[4])
            for i=1,#KEYS do redis.call('ZREMRANGEBYSCORE', KEYS[i], '-inf', now) end
            if maxCredential > 0 and redis.call('ZCARD', KEYS[3]) >= maxCredential then return {'CAPACITY'} end
            for i=1,#KEYS do redis.call('ZADD', KEYS[i], expire, leaseId) end
            return {'OK', leaseId, tostring(expire)}
            """;
    private static final String RELEASE_SCRIPT = """
            local leaseId = ARGV[1]
            for i=1,#KEYS do redis.call('ZREM', KEYS[i], leaseId) end
            return {'OK'}
            """;
    private static final String RENEW_SCRIPT = """
            local now = tonumber(ARGV[1])
            local expire = tonumber(ARGV[2])
            local leaseId = ARGV[3]
            for i=1,#KEYS do
              redis.call('ZREMRANGEBYSCORE', KEYS[i], '-inf', now)
              if redis.call('ZSCORE', KEYS[i], leaseId) then redis.call('ZADD', KEYS[i], expire, leaseId) end
            end
            return {'OK', tostring(expire)}
            """;
    private final Redis redis;
    private final GatewayRuntimeConfig config;

    public RedisDispatchLeaseManager(Redis redis, GatewayRuntimeConfig config) {
        this.redis = redis;
        this.config = config;
    }

    @Override
    public Future<DispatchLease> acquire(DispatchCandidate candidate, String requestId, String attemptId) {
        long now = System.currentTimeMillis();
        long expire = now + Math.max(1_000L, config.dispatchLeaseTtl().toMillis());
        String leaseId = UUID.randomUUID().toString();
        return redis.send(Request.cmd(Command.EVAL)
                        .arg(ACQUIRE_SCRIPT).arg(3)
                        .arg(channelKey(candidate.providerChannelId()))
                        .arg(quotaKey(candidate.quotaScope()))
                        .arg(credentialKey(candidate.providerCredentialId()))
                        .arg(now).arg(expire).arg(leaseId).arg(candidate.maxConcurrentStreams()))
                .compose(response -> {
                    String status = response.get(0).toString();
                    if (!"OK".equals(status)) return Future.failedFuture("调度资源容量已满");
                    return Future.succeededFuture(new DispatchLease(leaseId, attemptId, candidate.routeTargetId(),
                            candidate.providerChannelId(), candidate.quotaScope(), candidate.billingAccountGroup(),
                            candidate.providerCredentialId(), Instant.ofEpochMilli(expire)));
                });
    }

    @Override
    public Future<Void> release(DispatchLease lease) {
        if (lease == null) return Future.succeededFuture();
        return redis.send(Request.cmd(Command.EVAL)
                .arg(RELEASE_SCRIPT).arg(3)
                .arg(channelKey(lease.providerChannelId()))
                .arg(quotaKey(lease.quotaScope()))
                .arg(credentialKey(lease.credentialId()))
                .arg(lease.dispatchLeaseId())).mapEmpty();
    }

    /** 流式长请求按固定周期续期，不在每个 SSE chunk 上续期，避免放大 Redis 压力。 */
    public Future<Void> renew(DispatchLease lease) {
        long now = System.currentTimeMillis();
        long expire = now + Math.max(1_000L, config.dispatchLeaseTtl().toMillis());
        return redis.send(Request.cmd(Command.EVAL)
                .arg(RENEW_SCRIPT).arg(3)
                .arg(channelKey(lease.providerChannelId()))
                .arg(quotaKey(lease.quotaScope()))
                .arg(credentialKey(lease.credentialId()))
                .arg(now).arg(expire).arg(lease.dispatchLeaseId())).mapEmpty();
    }

    @Override
    public long activeCount(String resourceType, String resourceId) {
        // Redis 活跃数在 acquire Lua 内原子读取；Planner 的本地 tie-break 不依赖这个同步读。
        return 0L;
    }

    private String channelKey(long id) { return "fluxora:dispatch:lease:channel:" + id; }
    private String quotaKey(String scope) { return "fluxora:dispatch:lease:quota:" + scope; }
    private String credentialKey(long id) { return "fluxora:dispatch:lease:credential:" + id; }
}
