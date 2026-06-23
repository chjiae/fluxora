package io.fluxora.platform.runtime.mapper;

import java.time.Instant;

/** 租户鉴权快照的安全字段；结算币种保留给未来价格/结算演进。 */
public record RuntimeAuthTenantRow(Long tenantId, boolean enabled, Instant expireAt,
                                   Instant deletedAt, String settlementCurrencyCode) {
}
