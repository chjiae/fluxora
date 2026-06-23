package io.fluxora.platform.model.dto;

import java.time.Instant;

/**
 * 候选映射列表投影：聚合候选与通道关键信息便于前端表格直接展示。
 * 不暴露 deletedAt；不包含上游 ID 之外的凭证、地址、租户码等敏感字段。
 */
public record TenantModelCandidateMappingSummary(
        Long id,
        Long tenantId,
        Long tenantModelId,
        Long providerChannelModelId,
        Long providerChannelId,
        String channelName,
        String upstreamModelId,
        String upstreamDisplayName,
        boolean supportsStreaming,
        boolean supportsToolCalling,
        boolean supportsVision,
        boolean supportsCache,
        boolean enabled,
        /** 候选当前是否可用：候选启用且未删除、通道启用且未删除 */
        boolean candidateAvailable,
        String remark,
        Instant createdAt,
        Instant updatedAt
) {
}
