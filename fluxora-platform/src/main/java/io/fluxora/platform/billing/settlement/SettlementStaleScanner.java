package io.fluxora.platform.billing.settlement;

import java.time.Instant;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/** 定期兜底只将长期缺失终态的已开始请求转为待对账，不推断扣费或不扣费。 */
@Component
public class SettlementStaleScanner {
    private final BillingSettlementService service;
    private final long staleAfterMs;

    public SettlementStaleScanner(BillingSettlementService service,
                                  @Value("${fluxora.billing.settlement.stale-after-ms:900000}") long staleAfterMs) {
        this.service = service;
        this.staleAfterMs = Math.max(60_000L, staleAfterMs);
    }

    @Scheduled(fixedDelayString = "${fluxora.billing.settlement.stale-scan-delay-ms:60000}")
    public void scan() {
        service.moveStaleRequestsToReconciliation(Instant.now().minusMillis(staleAfterMs));
    }
}
