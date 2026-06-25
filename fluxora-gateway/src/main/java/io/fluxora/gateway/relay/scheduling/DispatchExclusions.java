package io.fluxora.gateway.relay.scheduling;

import java.util.HashSet;
import java.util.Set;

/** 单个 requestId 内的排除集合；重试只追加排除，不长期固定某个用户或模型的选择。 */
public final class DispatchExclusions {
    private final Set<Long> credentialIds;
    private final Set<String> quotaScopes;
    private final Set<String> billingAccountGroups;
    private final Set<Long> providerChannelCredentialIds;
    private final Set<Long> routeTargetIds;
    private final Set<Long> providerChannelIds;

    private DispatchExclusions(Set<Long> credentialIds, Set<String> quotaScopes, Set<String> billingAccountGroups,
                               Set<Long> providerChannelCredentialIds, Set<Long> routeTargetIds,
                               Set<Long> providerChannelIds) {
        this.credentialIds = credentialIds;
        this.quotaScopes = quotaScopes;
        this.billingAccountGroups = billingAccountGroups;
        this.providerChannelCredentialIds = providerChannelCredentialIds;
        this.routeTargetIds = routeTargetIds;
        this.providerChannelIds = providerChannelIds;
    }

    public static DispatchExclusions none() {
        return new DispatchExclusions(new HashSet<>(), new HashSet<>(), new HashSet<>(), new HashSet<>(),
                new HashSet<>(), new HashSet<>());
    }

    public DispatchExclusions excludeCredential(long id) { credentialIds.add(id); return this; }
    public DispatchExclusions excludeQuotaScope(String scope) { if (scope != null) quotaScopes.add(scope); return this; }
    public DispatchExclusions excludeBillingAccountGroup(String group) { if (group != null) billingAccountGroups.add(group); return this; }
    public DispatchExclusions excludeProviderChannelCredential(long id) { providerChannelCredentialIds.add(id); return this; }
    public DispatchExclusions excludeRouteTarget(long id) { routeTargetIds.add(id); return this; }
    public DispatchExclusions excludeProviderChannel(long id) { providerChannelIds.add(id); return this; }

    boolean accepts(DispatchCandidate candidate) {
        return !credentialIds.contains(candidate.providerCredentialId())
                && !quotaScopes.contains(candidate.quotaScope())
                && !billingAccountGroups.contains(candidate.billingAccountGroup())
                && !providerChannelCredentialIds.contains(candidate.providerChannelCredentialId())
                && !routeTargetIds.contains(candidate.routeTargetId())
                && !providerChannelIds.contains(candidate.providerChannelId());
    }
}
