package io.fluxora.platform.billing.reservation;

/** 人工对账输入：金额仅确认结算时必填，原因用于不可变审计且不允许记录敏感上游内容。 */
public record ReconciliationActionRequest(String finalAmount, String reason) {
}
