package io.fluxora.platform.runtime.mapper;

/** Redis 敏感凭证 Scope 的安全定位行；不读取密文，只用于批量影响范围计算。 */
public record RuntimeCredentialScopeRow(Long tenantId, Long credentialId) {
}
