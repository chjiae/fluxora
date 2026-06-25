package io.fluxora.gateway.relay.runtime;

import io.fluxora.gateway.relay.scheduling.DispatchCandidate;

/** Gateway 调度时读取的运行时可用性视图；实现不得访问数据库或上游网络。 */
@FunctionalInterface
public interface RuntimeAvailabilitySnapshot {
    RuntimeAvailabilitySnapshot ALWAYS_AVAILABLE = ignored -> true;

    boolean accepts(DispatchCandidate candidate);
}
