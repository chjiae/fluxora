package io.fluxora.platform.upstream.credential.dto;

/**
 * 凭证元数据编辑请求。
 * 仅允许修改名称、优先级、权重与备注；不包含 plaintext，不会覆盖已有密文。
 */
public record UpdateCredentialRequest(
        String name,
        Integer priority,
        Integer weight,
        String remark,
        String authType) {
}
