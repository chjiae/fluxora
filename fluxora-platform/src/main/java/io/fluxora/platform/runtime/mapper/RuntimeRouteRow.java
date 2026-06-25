package io.fluxora.platform.runtime.mapper;

import java.math.BigDecimal;
import java.time.Instant;

/**
 * TenantModelRoute 快照的扁平查询行。
 * 价格、路由和目标由同一 SQL 读取，Projector 再写为单个原子执行包，避免 Gateway 跨版本拼接。
 */
public record RuntimeRouteRow(Long tenantId, Long tenantModelId, String tenantModelCode, Instant tenantModelCreatedAt,
                               boolean tenantModelEnabled, boolean supportsStreaming, long maxInputTokens,
                               long maxOutputTokens, long maxCacheWriteTokens, long maxCacheReadTokens,
                               long defaultOutputTokens,
                              boolean supportsToolCalling, boolean supportsVision, boolean supportsCache,
                              String inboundProtocol, Long priceId, Integer priceVersion,
                              String currencyCode, BigDecimal inputPricePerMillion,
                              BigDecimal outputPricePerMillion, BigDecimal cacheWritePricePerMillion,
                              BigDecimal cacheReadPricePerMillion, Instant priceEffectiveAt,
                              Instant priceExpiresAt, Long routeId, boolean routeEnabled,
                              Long routeTargetId, Long mappingId, Long providerChannelId,
                              Long providerChannelModelId, Integer priority, Integer weight,
                              boolean targetEnabled, boolean mappingEnabled, boolean candidateEnabled,
                              boolean channelEnabled, String outboundProtocol, String upstreamModelId,
                              String normalizedBaseUrl, Integer connectTimeoutMs, Integer readTimeoutMs,
                              Long providerChannelCredentialId, Long providerCredentialId,
                              Long credentialVersion, String credentialAuthType,
                              boolean credentialBindingEnabled, boolean credentialEnabled,
                              String billingAccountGroup, String quotaScope, Integer credentialTrafficWeight,
                              Integer credentialMaxConcurrentStreams, String targetRuntimeState,
                              Instant targetCooldownUntil, String channelRuntimeState, Instant channelCooldownUntil,
                              String candidateRuntimeState, Instant candidateCooldownUntil,
                              String bindingRuntimeState, Instant bindingCooldownUntil,
                              String credentialRuntimeState, Instant credentialCooldownUntil,
                              String billingRuntimeState, Instant billingCooldownUntil,
                              String quotaRuntimeState, Instant quotaCooldownUntil,
                              boolean hasUsableCredential, Long credentialPoolVersion) {
}
