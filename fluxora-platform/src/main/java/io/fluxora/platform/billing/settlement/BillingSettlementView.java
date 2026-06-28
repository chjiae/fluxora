package io.fluxora.platform.billing.settlement;

import java.math.BigDecimal;
import java.time.Instant;

/** 管理端待对账安全视图，不返回余额、Redis、路由目标、凭证或上游错误正文。 */
public record BillingSettlementView(String requestId, Long tenantId, Long userId, String tenantModelCode,
                                    String status, BigDecimal actualAmount, BigDecimal outstandingAmount,
                                    String reasonCode, Instant createdAt) {
    static BillingSettlementView from(BillingSettlementRow row) {
        return new BillingSettlementView(row.requestId(), row.tenantId(), row.userId(), row.tenantModelCode(),
                row.status(), row.actualAmount(), row.outstandingAmount(), row.reasonCode(), row.createdAt());
    }
}
