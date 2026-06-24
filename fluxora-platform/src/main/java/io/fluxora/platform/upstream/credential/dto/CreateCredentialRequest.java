package io.fluxora.platform.upstream.credential.dto;

/**
 * 创建单个凭证请求。
 * plaintext 仅在请求处理期间存在；创建成功后任何接口都不再返回。
 */
public record CreateCredentialRequest(
        Long providerChannelId,
        String plaintext,
        String name,
        Integer priority,
        Integer weight,
        String remark,
        String authType) {
}
