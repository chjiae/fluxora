package io.fluxora.gateway.observability;

import java.util.UUID;

/**
 * 请求追踪标识只使用随机 UUID，不编码 API Key、用户、模型或上游信息。
 * 标准 UUID 格式便于运维查询与跨服务日志关联，同时不向客户端泄露业务身份。
 */
public final class RelayRequestId {
    private RelayRequestId() {
    }

    public static String next() {
        return UUID.randomUUID().toString();
    }
}
