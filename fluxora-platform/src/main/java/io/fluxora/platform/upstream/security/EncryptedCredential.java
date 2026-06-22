package io.fluxora.platform.upstream.security;

/**
 * 凭证加密后的持久化材料。
 * 该类型只在服务层与持久化层之间流转，绝不能作为接口响应 DTO。
 */
public record EncryptedCredential(String ciphertext, String initializationVector, String encryptionVersion) {
}
