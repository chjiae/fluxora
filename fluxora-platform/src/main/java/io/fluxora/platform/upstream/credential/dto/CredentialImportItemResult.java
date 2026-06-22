package io.fluxora.platform.upstream.credential.dto;

/**
 * 批量导入单行处理结果。
 * 仅用于结果展示与日志统计，不携带完整凭证明文。
 */
public enum CredentialImportItemResult {
    /** 成功导入 */
    IMPORTED,
    /** 同一批次内重复，已跳过 */
    SKIPPED_BATCH_DUPLICATE,
    /** 当前租户已存在（启用或停用），已跳过 */
    SKIPPED_EXISTING,
    /** 凭证格式无效（空行或仅空白），未导入 */
    INVALID,
    /** 超过单次导入数量限制，未导入 */
    OVER_LIMIT,
    /** 导入过程中并发写入导致已存在，已跳过 */
    SKIPPED_CONCURRENT
}
