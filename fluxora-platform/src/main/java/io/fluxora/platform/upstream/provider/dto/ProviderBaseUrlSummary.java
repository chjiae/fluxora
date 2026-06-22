package io.fluxora.platform.upstream.provider.dto;

import java.time.Instant;

/**
 * 接入基础地址对外摘要。
 * 不含 deletedAt；status 派生为 ENABLED / DISABLED。
 */
public record ProviderBaseUrlSummary(
        Long id,
        Long providerId,
        String protocol,
        String originalBaseUrl,
        String normalizedBaseUrl,
        String displayName,
        String remark,
        String status,
        Instant createdAt,
        Instant updatedAt) {
}
