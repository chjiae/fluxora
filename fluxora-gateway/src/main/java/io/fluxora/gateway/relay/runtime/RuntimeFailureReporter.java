package io.fluxora.gateway.relay.runtime;

import io.fluxora.gateway.observability.RelayEventPublisher;

/** 运行时故障事件上报端口；只做可靠投递，不直接修改运行时状态。 */
public final class RuntimeFailureReporter {
    private final RelayEventPublisher publisher;

    public RuntimeFailureReporter(RelayEventPublisher publisher) {
        this.publisher = publisher;
    }

    public static RuntimeFailureReporter noop() {
        return new RuntimeFailureReporter(null);
    }

    public void report(long tenantId, RuntimeIncident incident) {
        if (publisher == null) return;
        publisher.publishRuntimeFailure(RuntimeFailureEvent.from(tenantId, incident));
    }
}
