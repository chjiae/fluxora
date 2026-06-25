package io.fluxora.platform.upstream.credential.dto;

import java.time.Instant;

/**
 * 上游凭证对外摘要。
 * 严禁包含 ciphertext、initializationVector、credentialFingerprint、encryptionVersion、deletedAt。
 * maskedValue 仅展示前缀与尾号，不还原完整凭证。
 */
public record ProviderCredentialSummary(
        Long id,
        Long tenantId,
        Long providerChannelId,
        String name,
        String credentialType,
        String authType,
        String maskedValue,
        String status,
        int priority,
        int weight,
        String runtimeState,
        Instant lastFailedAt,
        String lastFailureKind,
        Instant cooldownUntil,
        String billingAccountGroup,
        String quotaScope,
        int trafficWeight,
        int maxConcurrentStreams,
        long boundChannelCount,
        String remark,
        Instant createdAt,
        Instant updatedAt) {
}
