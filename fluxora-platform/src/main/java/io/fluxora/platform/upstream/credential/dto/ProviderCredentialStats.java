package io.fluxora.platform.upstream.credential.dto;

/**
 * 指定通道下凭证的聚合指标，供通道详情凭证区展示。
 * 单次聚合 SQL，过滤 deleted_at IS NULL。
 */
public record ProviderCredentialStats(
        long total,
        long enabled,
        long disabled) {
}
