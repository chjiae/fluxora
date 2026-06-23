package io.fluxora.platform.model.dto;

/**
 * 租户模型聚合指标条数据。
 * 由 Mapper 中单次聚合 SQL 计算（COUNT(*) FILTER (WHERE …)），不允许前端遍历列表自统计。
 */
public record TenantModelStats(
        long total,
        long enabled,
        long disabled,
        long draft,
        /** 缺少有效价格或缺少路由的模型数；用于 MetricStrip 的「需关注」语义色 */
        long missingPrice,
        long missingRoute
) {
}
