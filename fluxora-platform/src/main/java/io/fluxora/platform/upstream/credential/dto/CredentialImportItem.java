package io.fluxora.platform.upstream.credential.dto;

/**
 * 批量导入结果明细中的一行。
 * maskedValue 为脱敏标识；reason 为用户可理解的安全原因，不含完整凭证或指纹。
 */
public record CredentialImportItem(
        int lineNumber,
        String maskedValue,
        CredentialImportItemResult result,
        String reason) {
}
