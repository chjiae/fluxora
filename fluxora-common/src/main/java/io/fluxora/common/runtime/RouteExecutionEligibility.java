package io.fluxora.common.runtime;

import java.time.Instant;

/**
 * 租户模型执行资格的唯一纯规则。
 *
 * Gateway 真实选路与 Platform 模型目录均调用本类，防止列表展示模型和实际调用集合漂移。
 * 该类只判断运行时快照中的公开执行元数据，绝不接触凭证明文、上游地址或数据库。
 */
public final class RouteExecutionEligibility {
    private RouteExecutionEligibility() {
    }

    /** 模型、路由与当前价格共同构成可执行请求的基础条件。 */
    public static boolean baseRouteCallable(boolean tenantModelEnabled, boolean routeEnabled,
                                            boolean priceAvailable, Instant effectiveAt, Instant expiresAt,
                                            Instant now) {
        return tenantModelEnabled && routeEnabled && priceAvailable
                && (effectiveAt == null || !effectiveAt.isAfter(now))
                && (expiresAt == null || expiresAt.isAfter(now));
    }

    /** 单个目标必须能够以相同协议、正权重和可用凭证真实派发。 */
    public static boolean targetCallable(boolean targetEnabled, boolean mappingEnabled, boolean candidateEnabled,
                                         boolean channelEnabled, boolean hasUsableCredential,
                                         String inboundProtocol, String outboundProtocol,
                                         Integer priority, Integer weight, Long routeTargetId,
                                         Long providerChannelId, Long providerChannelModelId) {
        return targetEnabled && mappingEnabled && candidateEnabled && channelEnabled && hasUsableCredential
                && inboundProtocol != null && inboundProtocol.equals(outboundProtocol)
                && priority != null && priority >= 0 && weight != null && weight > 0
                && positive(routeTargetId) && positive(providerChannelId) && positive(providerChannelModelId);
    }

    private static boolean positive(Long value) {
        return value != null && value > 0L;
    }
}
