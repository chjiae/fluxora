package io.fluxora.platform.runtime;

import io.fluxora.platform.runtime.mapper.RuntimeMapper;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 控制面唯一的运行时变更入口。
 * 业务 Service 只记录发生变化的聚合，Scope 推导、Redis Key 和通知格式全部由运行时模块集中处理。
 */
@Service
public class RuntimeOutboxService {
    private final RuntimeMapper runtimeMapper;

    public RuntimeOutboxService(RuntimeMapper runtimeMapper) {
        this.runtimeMapper = runtimeMapper;
    }

    /** 与调用方业务写入加入同一个事务；业务事务回滚时 Outbox 意图也必须回滚。 */
    @Transactional
    public void record(Long tenantId, String aggregateType, Long aggregateId, String mutationType, String impactHint) {
        if (aggregateType == null || aggregateType.isBlank() || mutationType == null || mutationType.isBlank()) {
            throw new IllegalArgumentException("运行时变更类型不能为空");
        }
        runtimeMapper.insertOutbox(tenantId, aggregateType, aggregateId, mutationType, impactHint);
    }
}
