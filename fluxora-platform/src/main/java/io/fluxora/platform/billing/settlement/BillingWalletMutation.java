package io.fluxora.platform.billing.settlement;

import java.math.BigDecimal;

/** 钱包直接扣费的原子 UPDATE 返回值，只包含真实余额变更前后快照。 */
public record BillingWalletMutation(BigDecimal balanceBefore, BigDecimal balanceAfter) {
}
