package io.fluxora.platform.upstream.provider.dto;

/**
 * 指定厂商下接入地址的聚合指标。
 * 单次聚合 SQL，过滤 deleted_at IS NULL；按协议分布便于排查覆盖。
 */
public record ProviderBaseUrlStats(
        long total,
        long enabled,
        long disabled,
        long openai,
        long anthropic) {
}
