package io.fluxora.platform.billing.reservation;

import java.math.BigDecimal;

/** 请求日志回写的最小安全结算视图，不含钱包主键、上游信息或人工审计备注。 */
public record ReservationFinalization(String status, BigDecimal reservationAmount, BigDecimal actualAmount,
                                      BigDecimal releasedAmount, BigDecimal outstandingAmount) {
    static ReservationFinalization none() { return new ReservationFinalization(null, null, null, null, null); }
    static ReservationFinalization from(BillingReservationRow row) {
        return new ReservationFinalization(row.status(), row.reservationAmount(), row.actualAmount(),
                row.releasedAmount(), row.outstandingAmount());
    }
}
