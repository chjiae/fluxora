package io.fluxora.gateway.observability;

import io.vertx.core.Future;
import java.util.Map;

/** Redis Stream 的最小端口，便于在不连接 Redis 的单测中验证重试与有界队列策略。 */
@FunctionalInterface
interface RelayEventStreamClient {
    Future<Void> append(Map<String, String> fields);
}
