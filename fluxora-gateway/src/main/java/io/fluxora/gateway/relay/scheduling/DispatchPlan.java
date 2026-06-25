package io.fluxora.gateway.relay.scheduling;

import io.fluxora.gateway.route.RouteSelection;
import io.vertx.core.json.JsonObject;

/** 一次 Attempt 的实际调度结果；只在 Gateway 请求作用域内使用。 */
public record DispatchPlan(RouteSelection routeSelection, JsonObject credentialRef, DispatchLease lease,
                           int priorityTier, String selectionReason, String schedulerMode) {
    public long routeTargetId() { return routeSelection.routeTargetId(); }
    public long providerChannelId() { return routeSelection.providerChannelId(); }
    public long providerChannelModelId() { return routeSelection.providerChannelModelId(); }
    public long providerChannelCredentialId() { return credentialRef.getLong("providerChannelCredentialId", providerCredentialId()); }
    public long providerCredentialId() { return credentialRef.getLong("providerCredentialId"); }
    public String quotaScope() { return credentialRef.getString("quotaScope", "credential:" + providerCredentialId()); }
    public String billingAccountGroup() { return credentialRef.getString("billingAccountGroup", "credential:" + providerCredentialId()); }
}
