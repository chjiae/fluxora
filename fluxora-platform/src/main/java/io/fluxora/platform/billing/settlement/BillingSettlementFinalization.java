package io.fluxora.platform.billing.settlement;

import java.math.BigDecimal;

/** 请求日志回写的最小安全结算视图，不包含钱包主键、余额或上游敏感信息。 */
public record BillingSettlementFinalization(String status, BigDecimal actualAmount,
                                            BigDecimal outstandingAmount) {
    public static BillingSettlementFinalization none() {
        return new BillingSettlementFinalization(null, null, null);
    }

    public static BillingSettlementFinalization from(BillingSettlementRow row) {
        return new BillingSettlementFinalization(row.status(), row.actualAmount(), row.outstandingAmount());
    }
}
