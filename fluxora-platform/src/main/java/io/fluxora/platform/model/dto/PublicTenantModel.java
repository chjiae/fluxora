package io.fluxora.platform.model.dto;

/**
 * C 端公开目录条目；只暴露当前租户已发布、可用且有完整配置的模型最小信息。
 * 绝不包含通道、上游模型、候选、映射、路由、凭证、内部价格版本号或 deletedAt。
 */
public record PublicTenantModel(
        Long id,
        String modelCode,
        String displayName,
        String description,
        boolean supportsStreaming,
        boolean supportsToolCalling,
        boolean supportsVision,
        boolean supportsCache,
        String currencyCode,
        String inputPricePerMillion,
        String outputPricePerMillion,
        /** 不支持缓存时为 null */
        String cacheWritePricePerMillion,
        String cacheReadPricePerMillion
) {
}
