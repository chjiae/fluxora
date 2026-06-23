package io.fluxora.platform.model.discovery;

/**
 * 单条同步结果：候选标识与安全原因。
 */
public record SyncItemResult(
        /** 上游返回的模型标识（可能为空/不合法） */
        String upstreamModelId,
        /** 结果分类 */
        String result,
        /** 用户可理解的安全原因；不含原始上游响应或异常堆栈 */
        String reason
) {
}