package io.fluxora.gateway.relay;

import io.vertx.core.json.JsonObject;

/**
 * 流式响应只保留小型 usage 字段，不缓冲消息正文或工具参数。
 * Collector 在每个已解析的 SSE data 事件上增量合并，结束时即可生成终态审计事件。
 */
final class RelayUsageCollector {
    private final RelayHandler handler;
    private RelayUsage usage = RelayUsage.unknown();

    RelayUsageCollector(RelayHandler handler) {
        this.handler = handler;
    }

    void accept(JsonObject event) {
        usage = usage.merge(handler.extractUsage(event));
    }

    RelayUsage currentUsage() {
        return usage;
    }
}
