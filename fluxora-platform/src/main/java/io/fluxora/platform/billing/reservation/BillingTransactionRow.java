package io.fluxora.platform.billing.reservation;

import java.math.BigDecimal;

/** 仅结算服务构造的不可变余额流水参数。 */
public record BillingTransactionRow(Long tenantId, Long userId, String direction, BigDecimal delta,
                                    BigDecimal balanceBefore, BigDecimal balanceAfter,
                                    BigDecimal frozenBalanceBefore, BigDecimal frozenBalanceAfter,
                                    String transactionType, Long reservationId, String reason) {
}
