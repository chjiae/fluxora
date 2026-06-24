package io.fluxora.platform.runtime;

/**
 * 敏感运行时凭证快照的最小控制面读取行。
 * 仅 SnapshotBuilder 使用密文列，任何 Controller、普通 DTO 或路由快照都不得引用本类型。
 */
public record RuntimeCredentialRow(Long tenantId, Long providerCredentialId, Long credentialVersion,
                                   String authType, boolean enabled, boolean deleted,
                                   String ciphertext, String initializationVector, String encryptionVersion) {
}
