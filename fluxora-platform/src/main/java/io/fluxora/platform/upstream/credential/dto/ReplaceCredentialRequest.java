package io.fluxora.platform.upstream.credential.dto;

/**
 * 替换凭证明文请求。
 * 替换是明确独立操作：写入新的密文、随机向量、指纹与脱敏值，旧密文不再被引用。
 * plaintext 仅在请求处理期间存在，处理后不保留。
 */
public record ReplaceCredentialRequest(String plaintext) {
}
