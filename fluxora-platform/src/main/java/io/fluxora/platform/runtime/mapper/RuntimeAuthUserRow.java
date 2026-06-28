package io.fluxora.platform.runtime.mapper;

import java.time.Instant;

/**
 * 用户鉴权快照的安全字段。
 *
 * <p>billingEligibility 是 Platform 根据真实余额派生的最小准入状态；本记录不携带余额数值，
 * 避免 Gateway 持有钱包副本或自行计算可调用资格。</p>
 */
public record RuntimeAuthUserRow(Long tenantId, Long userId, boolean enabled, Instant deletedAt,
                                 String billingEligibility) {
}
