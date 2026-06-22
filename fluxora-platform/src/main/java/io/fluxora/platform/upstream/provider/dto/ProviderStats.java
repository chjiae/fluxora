package io.fluxora.platform.upstream.provider.dto;

/**
 * 上游厂商聚合指标，供管理页指标条使用。
 * 全部来自单次 COUNT(*) FILTER 聚合 SQL，过滤 deleted_at IS NULL。
 */
public record ProviderStats(
        long total,
        long platformShared,
        long tenantPrivate,
        long enabled,
        long disabled) {
}
