package io.fluxora.platform.model.dto;

import java.time.Instant;

/**
 * 模型路由摘要：仅展示路由级元数据 + 当前路由目标数量；目标明细由专属子接口返回。
 */
public record ModelRouteSummary(
        Long id,
        Long tenantId,
        Long tenantModelId,
        /** 入站协议；OPENAI / ANTHROPIC */
        String inboundProtocol,
        boolean enabled,
        String remark,
        /** 当前路由下未删除目标数量；用于列表辅助列与启用前置判断 */
        long targetCount,
        Instant createdAt,
        Instant updatedAt
) {
}
