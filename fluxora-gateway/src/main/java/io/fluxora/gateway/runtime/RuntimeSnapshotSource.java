package io.fluxora.gateway.runtime;

import io.vertx.core.Future;

/** 运行时快照来源抽象；生产实现只访问 Redis，测试实现可用内存替身验证热路径。 */
public interface RuntimeSnapshotSource {
    Future<RuntimeSnapshot> load(RuntimeScopeType scopeType, String scopeKey);
    Future<Long> manifestVersion(RuntimeScopeType scopeType, String scopeKey);
}
