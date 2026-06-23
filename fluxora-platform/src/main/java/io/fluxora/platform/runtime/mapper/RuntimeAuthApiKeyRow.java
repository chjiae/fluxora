package io.fluxora.platform.runtime.mapper;

import java.time.Instant;

/** API Key 鉴权快照的安全字段；不包含明文、前缀、密码、邮箱或余额。 */
public record RuntimeAuthApiKeyRow(Long apiKeyId, Long tenantId, Long userId, boolean enabled,
                                   Instant expireAt, Instant deletedAt, String lookupHash,
                                   int lookupHashVersion) {
}
