package io.fluxora.platform.runtime;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/** Redis 命名空间健康标记丢失时请求可重试的全量重建；不在 Gateway 或业务接口中回查 PostgreSQL。 */
@Component
public class RuntimeNamespaceRecovery {
    private static final Logger log = LoggerFactory.getLogger(RuntimeNamespaceRecovery.class);

    private final RuntimeRedisSnapshotStore snapshotStore;
    private final RuntimeOutboxService outboxService;
    private final RuntimeProperties properties;

    public RuntimeNamespaceRecovery(RuntimeRedisSnapshotStore snapshotStore, RuntimeOutboxService outboxService,
                                    RuntimeProperties properties) {
        this.snapshotStore = snapshotStore;
        this.outboxService = outboxService;
        this.properties = properties;
    }

    @Scheduled(initialDelayString = "${fluxora.runtime.recovery-delay-ms:30000}",
            fixedDelayString = "${fluxora.runtime.recovery-delay-ms:30000}")
    public void requestRebuildWhenNamespaceMissing() {
        if (!properties.isEnabled()) {
            return;
        }
        try {
            if (!snapshotStore.namespaceHealthy()) {
                outboxService.record(null, "RUNTIME_NAMESPACE", null, "FULL_REBUILD", "NAMESPACE_MISSING");
                log.warn("运行时 Redis 命名空间缺失，已写入全量快照重建任务");
            }
        } catch (RuntimeException exception) {
            // Redis 不可达时不能假装健康；下一个周期会重试，Gateway 同时按失败关闭处理。
            log.warn("无法检查运行时 Redis 命名空间健康状态");
        }
    }
}
