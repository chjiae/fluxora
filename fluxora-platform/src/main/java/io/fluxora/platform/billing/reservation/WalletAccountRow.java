package io.fluxora.platform.billing.reservation;

import java.math.BigDecimal;

/** 预冻结前读取既有额度账户归属与可用/冻结余额；不创建影子钱包。 */
public record WalletAccountRow(Long id, Long tenantId, Long userId, String currencyCode,
                               BigDecimal balance, BigDecimal frozenBalance) {
}
