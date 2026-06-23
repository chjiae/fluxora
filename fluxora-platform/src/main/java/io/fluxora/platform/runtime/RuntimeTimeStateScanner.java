package io.fluxora.platform.runtime;

import io.fluxora.platform.runtime.mapper.RuntimeMapper;
import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.List;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * 把自然到期/生效转为可靠 Outbox 事件；Gateway 仍逐请求比较时间，因此扫描延迟不会造成超期放行。
 */
@Component
public class RuntimeTimeStateScanner {
    private static final String API_KEY_CURSOR = "API_KEY_TIME_SCAN_CURSOR";
    private static final String TENANT_CURSOR = "TENANT_TIME_SCAN_CURSOR";
    private static final String PRICE_CURSOR = "PRICE_TIME_SCAN_CURSOR";

    private final RuntimeMapper runtimeMapper;
    private final RuntimeOutboxService outboxService;
    private final RuntimeProperties properties;

    public RuntimeTimeStateScanner(RuntimeMapper runtimeMapper, RuntimeOutboxService outboxService,
                                   RuntimeProperties properties) {
        this.runtimeMapper = runtimeMapper;
        this.outboxService = outboxService;
        this.properties = properties;
    }

    @Scheduled(fixedDelayString = "${fluxora.runtime.time-scan-delay-ms:30000}")
    public void scanTimeTransitions() {
        if (!properties.isEnabled()) {
            return;
        }
        Instant until = Instant.now();
        scan(API_KEY_CURSOR, until, runtimeMapper.findExpiredApiKeyIdsSince(cursor(API_KEY_CURSOR, until), until), "API_KEY", "TIME_EXPIRED");
        scan(TENANT_CURSOR, until, runtimeMapper.findExpiredTenantIdsSince(cursor(TENANT_CURSOR, until), until), "TENANT", "TIME_EXPIRED");
        scan(PRICE_CURSOR, until, runtimeMapper.findPriceChangedTenantModelIdsSince(cursor(PRICE_CURSOR, until), until), "TENANT_MODEL_PRICE", "TIME_TRANSITION");
    }

    private void scan(String cursorKey, Instant until, List<Long> ids, String aggregateType, String mutationType) {
        ids.forEach(id -> outboxService.record(null, aggregateType, id, mutationType, "TIME_SCAN"));
        runtimeMapper.upsertProjectionState(cursorKey, until.toString());
    }

    private Instant cursor(String key, Instant until) {
        return runtimeMapper.findProjectionState(key).map(value -> parseOrFallback(value, until)).orElse(until.minusSeconds(60));
    }

    private Instant parseOrFallback(String value, Instant until) {
        try {
            return Instant.parse(value);
        } catch (DateTimeParseException exception) {
            return until.minusSeconds(60);
        }
    }
}
