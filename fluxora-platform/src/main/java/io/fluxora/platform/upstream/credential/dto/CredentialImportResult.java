package io.fluxora.platform.upstream.credential.dto;

import java.util.List;

/**
 * 批量导入完整结果。
 * summary 提供汇总计数；items 提供逐行脱敏明细，关闭结果页后前端不再保留任何输入原文。
 */
public record CredentialImportResult(
        CredentialImportSummary summary,
        List<CredentialImportItem> items) {
}
