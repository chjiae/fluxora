package io.fluxora.platform.billing.reservation;

import java.time.Instant;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/** 定期兜底只将长期 RESERVED 变为待对账，绝不因超时自动返还冻结余额。 */
@Component
public class ReservationStaleScanner {
    private final ReservationSettlementService service;
    private final long staleAfterMs;

    public ReservationStaleScanner(ReservationSettlementService service,
                                   @Value("${fluxora.billing.reservation.stale-after-ms:900000}") long staleAfterMs) {
        this.service = service;
        this.staleAfterMs = Math.max(60_000L, staleAfterMs);
    }

    @Scheduled(fixedDelayString = "${fluxora.billing.reservation.stale-scan-delay-ms:60000}")
    public void scan() {
        service.moveStaleReservationsToReconciliation(Instant.now().minusMillis(staleAfterMs));
    }
}
