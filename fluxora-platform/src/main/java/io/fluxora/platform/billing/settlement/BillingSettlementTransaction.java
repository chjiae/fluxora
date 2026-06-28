package io.fluxora.platform.billing.settlement;

import java.math.BigDecimal;

/**
 * 直接结算写入额度流水的内部命令。
 *
 * <p>source 固定为 BILLING，operator 使用被扣费用户，避免暴露 Gateway 内部身份。</p>
 */
public record BillingSettlementTransaction(Long tenantId, Long userId, String direction, BigDecimal delta,
                                           BigDecimal balanceBefore, BigDecimal balanceAfter,
                                           String transactionType, Long billingSettlementId, String reason) {
}
