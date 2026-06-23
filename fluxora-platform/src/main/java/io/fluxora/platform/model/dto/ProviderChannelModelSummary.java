package io.fluxora.platform.model.dto;

import java.time.Instant;

/**
 * 上游候选模型列表/详情投影。
 * 不暴露 deletedAt；status 派生（ENABLED / DISABLED）。
 */
public record ProviderChannelModelSummary(
        Long id,
        Long tenantId,
        Long providerChannelId,
        String channelName,
        String upstreamModelId,
        String upstreamDisplayName,
        String sourceType,
        boolean supportsStreaming,
        boolean supportsToolCalling,
        boolean supportsVision,
        boolean supportsCache,
        String status,
        Instant lastSyncedAt,
        String lastSyncSummary,
        Instant createdAt,
        Instant updatedAt
) {
}
