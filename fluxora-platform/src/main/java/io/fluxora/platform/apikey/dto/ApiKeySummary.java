package io.fluxora.platform.apikey.dto;

import java.time.Instant;

/**
 * API Key 列表/详情对外 DTO。
 *
 * 显式：仅 keyPrefix 公开；keyHash 与 plaintext 永不出现在此 DTO。
 * status 来自服务端派生（ENABLED / DISABLED / EXPIRED）。
 */
public record ApiKeySummary(
        Long id,
        Long tenantId,
        String tenantCode,
        String tenantName,
        Long userId,
        String username,
        String userDisplayName,
        String name,
        String keyPrefix,
        String status,
        Instant expireAt,
        Instant lastUsedAt,
        Instant createdAt,
        Instant updatedAt
) {}
