package io.fluxora.platform.upstream.channel.dto;

/**
 * 上游通道聚合指标。
 * 单次聚合 SQL，过滤 deleted_at IS NULL，按当前身份作用域统计。
 */
public record ProviderChannelStats(
        long total,
        long enabled,
        long disabled) {
}
