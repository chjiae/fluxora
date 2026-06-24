package io.fluxora.gateway.observability;

import io.vertx.core.Future;
import io.vertx.redis.client.Command;
import io.vertx.redis.client.Redis;
import io.vertx.redis.client.Request;
import java.util.Map;

/**
 * Vert.x Redis 客户端异步 XADD 实现。MAXLEN ~ 只限制观测 Stream 的历史长度，
 * 不影响 Platform 已写入 PostgreSQL 的审计记录；调用方不得等待该 Future 后再返回用户响应。
 */
final class RedisRelayEventStreamClient implements RelayEventStreamClient {
    private final Redis redis;
    private final String streamKey;
    private final int maxLength;

    RedisRelayEventStreamClient(Redis redis, String streamKey, int maxLength) {
        this.redis = redis;
        this.streamKey = streamKey;
        this.maxLength = maxLength;
    }

    @Override
    public Future<Void> append(Map<String, String> fields) {
        Request request = Request.cmd(Command.XADD).arg(streamKey).arg("MAXLEN").arg("~")
                .arg(Integer.toString(maxLength)).arg("*");
        fields.forEach((key, value) -> request.arg(key).arg(value));
        return redis.send(request).mapEmpty();
    }
}
