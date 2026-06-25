package io.fluxora.platform.model.dto;

import java.time.Instant;

/**
 * 租户模型列表/详情投影。
 * 不包含 deletedAt 时间戳；status 由服务层从 publish_status + enabled + deleted_at 派生。
 * 不暴露内部候选 ID、通道 ID、价格版本号等细节；明细字段由专属接口按需返回。
 */
public record TenantModelSummary(
        Long id,
        Long tenantId,
        String tenantName,
        String modelCode,
        String displayName,
        String description,
        boolean supportsStreaming,
        boolean supportsToolCalling,
        boolean supportsVision,
        boolean supportsCache,
        /** 客户端未声明时自动填入的默认输出 Token */
        long defaultOutputTokens,
        /** ENABLED / DISABLED / DRAFT；统一由服务层派生，前端展示需要等宽颜色对齐 */
        String status,
        /** 当前有效映射数量；可用于「需关注」筛选与列表辅助列 */
        long mappingCount,
        /** 是否存在当前有效价格；为 false 时启用按钮置灰 */
        boolean hasActivePrice,
        /** 当前路由数量（不区分协议；含 DRAFT/ENABLED）；本提交仅返回 0，路由领域在提交 4 落地 */
        long routeCount,
        Instant createdAt,
        Instant updatedAt
) {
}
