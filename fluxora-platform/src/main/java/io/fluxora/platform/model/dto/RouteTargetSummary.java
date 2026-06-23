package io.fluxora.platform.model.dto;

import java.time.Instant;

/**
 * 路由目标摘要：以 tenant_model_candidate_mapping 为事实来源；
 * 通道 ID 与上游标识快照仅作展示与未来审计冗余，写入仍以映射为准。
 */
public record RouteTargetSummary(
        Long id,
        Long tenantId,
        Long modelRouteId,
        Long tenantModelCandidateMappingId,
        Long providerChannelId,
        String channelName,
        String upstreamModelIdSnapshot,
        boolean enabled,
        int priority,
        int weight,
        String remark,
        /** 关联映射当前是否仍可用（映射启用 + 候选启用 + 通道启用 + 全部未删除） */
        boolean mappingAvailable,
        Instant createdAt,
        Instant updatedAt
) {
}
