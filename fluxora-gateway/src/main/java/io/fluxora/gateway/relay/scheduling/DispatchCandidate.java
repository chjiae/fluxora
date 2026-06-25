package io.fluxora.gateway.relay.scheduling;

import io.vertx.core.json.JsonObject;

/** Planner 内部候选；字段均来自运行时快照的安全引用，不包含凭证材料。 */
public record DispatchCandidate(JsonObject target, JsonObject credentialRef, int priorityTier, int channelWeight,
                                long routeTargetId, long providerChannelId, long providerChannelModelId,
                                long providerChannelCredentialId, long providerCredentialId,
                                String quotaScope, String billingAccountGroup, int credentialWeight,
                                int maxConcurrentStreams) {
}
