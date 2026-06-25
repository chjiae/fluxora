package io.fluxora.gateway.relay.scheduling;

import java.time.Instant;

/** 调度租约的安全元数据；不包含 BaseUrl、凭证明文或请求正文。 */
public record DispatchLease(String dispatchLeaseId, String attemptId, long routeTargetId, long providerChannelId,
                            String quotaScope, String billingAccountGroup, long credentialId, Instant expiresAt) {
}
