package io.fluxora.gateway.relay;

/**
 * 上游用量的可信程度。UNKNOWN 绝不能在后续链路中按零 Token 处理；
 * PARTIAL 表示当前已取得部分字段，但不可据此生成伪精确的最终金额。
 */
public enum RelayUsageStatus {
    REPORTED,
    PARTIAL,
    UNKNOWN,
    NOT_APPLICABLE
}
