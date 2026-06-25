package io.fluxora.platform.billing.reservation;

import java.math.BigDecimal;

/** 单条原子钱包 UPDATE 返回的前后快照，用于不可篡改流水审计。 */
public record WalletMutation(BigDecimal balanceBefore, BigDecimal balanceAfter,
                             BigDecimal frozenBalanceBefore, BigDecimal frozenBalanceAfter) {
}
