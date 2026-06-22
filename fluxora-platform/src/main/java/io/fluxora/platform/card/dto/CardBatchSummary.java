package io.fluxora.platform.card.dto;

import java.math.BigDecimal;
import java.time.Instant;

/**
 * 卡密批次对外摘要。
 * 含派生统计：可用 / 已核销 / 已停用 / 已过期 数量；通过聚合 SQL 实时计算。
 * 不暴露 created_by_id（隐私），仅展示 created_by_name。
 */
public record CardBatchSummary(
        Long id,
        Long tenantId,
        String tenantCode,
        String tenantName,
        String batchCode,
        String name,
        BigDecimal denomination,
        Integer totalCount,
        Integer availableCount,
        Integer usedCount,
        Integer disabledCount,
        Integer expiredCount,
        String status,
        Instant expireAt,
        Long createdById,
        String createdByName,
        Instant createdAt,
        Instant updatedAt
) {}