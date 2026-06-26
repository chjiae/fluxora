package io.fluxora.platform.runtime;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

/**
 * Platform 启动时强制全量重建所有运行时快照，确保 Gateway 不会读到旧版本数据结构。
 * Outbox 插入后由 RuntimeProjector 在下一个调度周期消费，不会阻塞启动。
 */
@Component
public class StartupSnapshotRebuilder {
    private static final Logger log = LoggerFactory.getLogger(StartupSnapshotRebuilder.class);

    private final RuntimeOutboxService outboxService;

    public StartupSnapshotRebuilder(RuntimeOutboxService outboxService) {
        this.outboxService = outboxService;
    }

    @EventListener(ApplicationReadyEvent.class)
    public void rebuildAllSnapshots() {
        log.info("应用启动完成，发起全量运行时快照重建");
        outboxService.recordFullyRebuild();
        log.info("FULL_REBUILD outbox 已写入，将在下一个投影周期消费");
    }
}
