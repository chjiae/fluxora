package io.fluxora.gateway.relay.orchestration;

import io.fluxora.gateway.relay.scheduling.DispatchExclusions;
import io.fluxora.gateway.relay.scheduling.DispatchPlan;
import io.vertx.core.json.JsonObject;
import java.time.Instant;
import java.util.UUID;

/** 一个客户端请求的 Attempt 上下文；requestId 固定，attemptId 每次内部尝试独立。 */
public final class RelayAttemptContext {
    private final String requestId;
    private final long tenantId;
    private final JsonObject routeSnapshot;
    private final int maxAttempts;
    private final long firstByteDeadlineEpochMs;
    private final DispatchExclusions exclusions = DispatchExclusions.none();
    private int attemptNo = 1;
    private DispatchPlan firstPlan;

    public RelayAttemptContext(String requestId, long tenantId, JsonObject routeSnapshot, DispatchPlan firstPlan,
                               int maxAttempts, long firstByteBudgetMs) {
        this.requestId = requestId;
        this.tenantId = tenantId;
        this.routeSnapshot = routeSnapshot;
        this.firstPlan = firstPlan;
        this.maxAttempts = maxAttempts;
        this.firstByteDeadlineEpochMs = Instant.now().toEpochMilli() + Math.max(1L, firstByteBudgetMs);
    }

    public String requestId() { return requestId; }
    public long tenantId() { return tenantId; }
    public JsonObject routeSnapshot() { return routeSnapshot; }
    public int attemptNo() { return attemptNo; }
    public int maxAttempts() { return maxAttempts; }
    public DispatchExclusions exclusions() { return exclusions; }
    public long remainingFirstByteBudgetMs() { return Math.max(0L, firstByteDeadlineEpochMs - System.currentTimeMillis()); }
    public String nextAttemptId() { return requestId + "-attempt-" + attemptNo + "-" + UUID.randomUUID(); }

    DispatchPlan consumeFirstPlan() {
        DispatchPlan plan = firstPlan;
        firstPlan = null;
        return plan;
    }

    boolean advanceAttempt() {
        attemptNo++;
        return attemptNo <= maxAttempts;
    }
}
