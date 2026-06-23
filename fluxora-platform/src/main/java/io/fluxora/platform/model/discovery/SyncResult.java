package io.fluxora.platform.model.discovery;

import java.util.List;

/**
 * 同步操作结果：汇总计数与逐项安全原因。
 * 不含原始上游响应、凭证、密文或内部网络信息。
 */
public record SyncResult(
        /** 通道中原先已有的候选数量（同步前 pre-check）；用于让用户感知变化幅度 */
        long existingBeforeSync,
        /** 本次同步新增的候选数量 */
        long added,
        /** 本次同步更新了 last_synced_at 的候选数量（仍存在） */
        long updated,
        /** 本次未返回的上游标识数量（仍然保留，不做物理删除） */
        long missing,
        /** 总失败行数 */
        int failed,
        /** 逐项失败原因（不含上游原始响应、堆栈或凭证） */
        List<SyncItemResult> failures
) {
}