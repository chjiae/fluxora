package io.fluxora.platform.billing.settlement;

/** 人工对账输入：金额仅确认扣费时必填，原因用于不可变审计且不允许记录敏感上游内容。 */
public record ReconciliationActionRequest(String finalAmount, String reason) {
}
