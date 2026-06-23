package io.fluxora.gateway.runtime;

import io.vertx.core.json.JsonObject;

/** 已通过 Manifest、schemaVersion 与 runtimeVersion 一致性验证的不可变快照。 */
public record RuntimeSnapshot(RuntimeScopeType scopeType, String scopeKey, long runtimeVersion, JsonObject payload) {
}
