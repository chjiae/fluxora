package io.fluxora.platform.upstream.channel.dto;

import java.time.Instant;

/**
 * 上游通道对外摘要。
 * 关联展示厂商名称、协议、规范化地址、归属租户名称与凭证数量，避免前端逐行加载关联数据。
 * 不含 deletedAt；status 派生为 ENABLED / DISABLED。
 */
public record ProviderChannelSummary(
        Long id,
        Long tenantId,
        String tenantName,
        Long providerId,
        String providerName,
        Long providerBaseUrlId,
        String protocol,
        String normalizedBaseUrl,
        String name,
        String status,
        int priority,
        int weight,
        int connectTimeoutMs,
        int readTimeoutMs,
        String remark,
        long credentialCount,
        Instant createdAt,
        Instant updatedAt) {
}
