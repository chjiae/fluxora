package io.fluxora.gateway.relay;

/**
 * Gateway 内部统一 Token 用量。四个桶互斥：缓存读取已从普通输入中剥离，
 * null 表示上游没有可靠给出该值，不能替换为零。
 */
public record RelayUsage(Long inputTokens, Long outputTokens, Long cacheWriteTokens,
                         Long cacheReadTokens, RelayUsageStatus status) {

    public static RelayUsage unknown() {
        return new RelayUsage(null, null, null, null, RelayUsageStatus.UNKNOWN);
    }

    public static RelayUsage from(Long inputTokens, Long outputTokens, Long cacheWriteTokens,
                                  Long cacheReadTokens) {
        RelayUsageStatus resolved = inputTokens == null && outputTokens == null
                ? RelayUsageStatus.UNKNOWN
                : inputTokens == null || outputTokens == null ? RelayUsageStatus.PARTIAL : RelayUsageStatus.REPORTED;
        return new RelayUsage(inputTokens, outputTokens, cacheWriteTokens, cacheReadTokens, resolved);
    }

    /**
     * SSE 生命周期中不同事件分别上报输入与最终输出；仅用新事件中非空字段覆盖，
     * 防止 message_delta 缺少输入字段时丢掉 message_start 已得到的可靠值。
     */
    public RelayUsage merge(RelayUsage next) {
        if (next == null) {
            return this;
        }
        return from(next.inputTokens != null ? next.inputTokens : inputTokens,
                next.outputTokens != null ? next.outputTokens : outputTokens,
                next.cacheWriteTokens != null ? next.cacheWriteTokens : cacheWriteTokens,
                next.cacheReadTokens != null ? next.cacheReadTokens : cacheReadTokens);
    }
}
