package io.fluxora.platform.billing.reservation;

/** Gateway 仅据此安全状态决定能否派发上游；不返回钱包、路由或凭证细节。 */
public record ReservationOutcome(String status, String reasonCode, String reservationAmount) {
    public boolean reserved() { return "RESERVED".equals(status); }
}
