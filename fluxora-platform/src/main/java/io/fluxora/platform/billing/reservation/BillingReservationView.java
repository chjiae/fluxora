package io.fluxora.platform.billing.reservation;

import java.math.BigDecimal;
import java.time.Instant;

/** 平台/用户安全展示投影：没有 API Key、凭证、BaseUrl、上游模型或消息正文。 */
public record BillingReservationView(String requestId, Long tenantId, Long userId, String tenantModelCode,
                                     String status, BigDecimal reservationAmount, BigDecimal actualAmount,
                                     BigDecimal releasedAmount, BigDecimal outstandingAmount, String reasonCode,
                                     String upstreamDispatchState, Instant createdAt, Instant updatedAt) {
    static BillingReservationView from(BillingReservationRow row) {
        return new BillingReservationView(row.requestId(), row.tenantId(), row.userId(), row.tenantModelCode(), row.status(),
                row.reservationAmount(), row.actualAmount(), row.releasedAmount(), row.outstandingAmount(), row.reasonCode(),
                row.upstreamDispatchState(), row.createdAt(), row.updatedAt());
    }
}
