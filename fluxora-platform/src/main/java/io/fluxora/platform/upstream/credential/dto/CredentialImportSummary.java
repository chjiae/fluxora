package io.fluxora.platform.upstream.credential.dto;

/**
 * 批量导入汇总。
 * 各计数相互独立；totalRead 为解析后的非空行数，imported 为实际写入条数。
 */
public record CredentialImportSummary(
        int totalRead,
        int imported,
        int skippedBatchDuplicate,
        int skippedExisting,
        int invalid,
        int overLimit,
        int concurrentDuplicate) {
}
