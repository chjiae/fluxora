package io.fluxora.platform.runtime;

import com.fasterxml.jackson.databind.JsonNode;
import io.fluxora.platform.runtime.mapper.RuntimeMapper;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Outbox 消费者：按 Scope 分配单调版本，构建不可变快照，并在 Manifest 切换后才发布失效事件。
 * 重复消费会得到更高版本但不会回退 Manifest，因此对 Gateway 是幂等且安全的。
 */
@Component
public class RuntimeProjector {
    private static final Logger log = LoggerFactory.getLogger(RuntimeProjector.class);

    private final RuntimeMapper runtimeMapper;
    private final RuntimeImpactResolver impactResolver;
    private final RuntimeSnapshotBuilder snapshotBuilder;
    private final RuntimeRedisSnapshotStore snapshotStore;
    private final RuntimeProperties properties;
    private final String workerId = "platform-" + UUID.randomUUID();

    public RuntimeProjector(RuntimeMapper runtimeMapper, RuntimeImpactResolver impactResolver,
                            RuntimeSnapshotBuilder snapshotBuilder, RuntimeRedisSnapshotStore snapshotStore,
                            RuntimeProperties properties) {
        this.runtimeMapper = runtimeMapper;
        this.impactResolver = impactResolver;
        this.snapshotBuilder = snapshotBuilder;
        this.snapshotStore = snapshotStore;
        this.properties = properties;
    }

    @Scheduled(fixedDelayString = "${fluxora.runtime.projector-delay-ms:500}")
    public void poll() {
        if (!properties.isEnabled()) {
            return;
        }
        runtimeMapper.recoverStaleProcessing(Instant.now().minus(Duration.ofMinutes(5)));
        List<RuntimeOutboxEvent> events = runtimeMapper.claimDueBatch(workerId, properties.getProjectorBatchSize());
        for (RuntimeOutboxEvent event : events) {
            try {
                project(event);
            } catch (RuntimeException exception) {
                scheduleRetry(event);
            }
        }
    }

    /** 对一个已被领取的 Outbox 事件执行投影，供集成测试和恢复任务复用。 */
    public void project(RuntimeOutboxEvent event) {
        Set<RuntimeScope> scopes = impactResolver.resolve(event);
        for (RuntimeScope scope : scopes) {
            long runtimeVersion = runtimeMapper.allocateVersion(scope.type().name(), scope.scopeKey());
            JsonNode snapshot = snapshotBuilder.build(scope, runtimeVersion, event);
            snapshotStore.writeSnapshotAndSwitch(scope, runtimeVersion, snapshot, event);
        }
        if ("RUNTIME_NAMESPACE".equals(event.aggregateType()) || "FULL_REBUILD".equals(event.mutationType())) {
            snapshotStore.markNamespaceHealthy();
        }
        runtimeMapper.markCompleted(event.id());
        log.debug("运行时投影完成：outboxId={}, scopeCount={}", event.id(), scopes.size());
    }

    private void scheduleRetry(RuntimeOutboxEvent event) {
        long seconds = Math.min(300L, 1L << Math.min(8, Math.max(1, event.attemptCount())));
        runtimeMapper.markRetry(event.id(), Instant.now().plus(Duration.ofSeconds(seconds)),
                "运行时投影失败，请检查基础设施健康状态");
        log.warn("运行时投影失败，将在退避后重试：outboxId={}, attempt={}", event.id(), event.attemptCount());
    }
}
