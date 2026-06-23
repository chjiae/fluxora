package io.fluxora.platform.runtime.mapper;

import java.time.Instant;

/** 用户鉴权快照的安全字段。 */
public record RuntimeAuthUserRow(Long tenantId, Long userId, boolean enabled, Instant deletedAt) {
}
