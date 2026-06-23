package io.fluxora.platform.runtime.mapper;

import io.fluxora.platform.runtime.RuntimeOutboxEvent;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

/** 所有运行时 Outbox、影响分析与快照读取 SQL 的唯一 MyBatis 契约。 */
@Mapper
public interface RuntimeMapper {
    void insertOutbox(@Param("tenantId") Long tenantId, @Param("aggregateType") String aggregateType,
                      @Param("aggregateId") Long aggregateId, @Param("mutationType") String mutationType,
                      @Param("impactHint") String impactHint);

    List<RuntimeOutboxEvent> claimDueBatch(@Param("workerId") String workerId, @Param("limit") int limit);
    int recoverStaleProcessing(@Param("olderThan") Instant olderThan);
    void markCompleted(@Param("id") Long id);
    void markRetry(@Param("id") Long id, @Param("nextRetryAt") Instant nextRetryAt,
                   @Param("errorSummary") String errorSummary);
    long allocateVersion(@Param("scopeType") String scopeType, @Param("scopeKey") String scopeKey);

    Optional<String> findApiKeyLookupHash(@Param("apiKeyId") Long apiKeyId);
    Optional<RuntimeScopeRow> findAuthUserScope(@Param("userId") Long userId);
    Optional<RuntimeScopeRow> findAuthTenantScope(@Param("tenantId") Long tenantId);
    List<RuntimeScopeRow> findRouteScopesByTenantModel(@Param("tenantModelId") Long tenantModelId);
    List<RuntimeScopeRow> findRouteScopesByRoute(@Param("routeId") Long routeId);
    List<RuntimeScopeRow> findRouteScopesByTarget(@Param("targetId") Long targetId);
    List<RuntimeScopeRow> findRouteScopesByMapping(@Param("mappingId") Long mappingId);
    List<RuntimeScopeRow> findRouteScopesByCandidate(@Param("candidateId") Long candidateId);
    List<RuntimeScopeRow> findRouteScopesByChannel(@Param("channelId") Long channelId);
    List<RuntimeScopeRow> findRouteScopesByCredential(@Param("credentialId") Long credentialId);
    List<RuntimeScopeRow> findRouteScopesByProvider(@Param("providerId") Long providerId);
    List<RuntimeScopeRow> findRouteScopesByBaseUrl(@Param("baseUrlId") Long baseUrlId);
    List<RuntimeScopeRow> findAllRouteScopes();
    List<String> findAllApiKeyLookupHashes();
    List<RuntimeAuthUserScopeRow> findAllAuthUserScopes();
    List<RuntimeScopeRow> findAllAuthTenantScopes();

    Optional<RuntimeAuthApiKeyRow> findAuthApiKeySnapshot(@Param("lookupHash") String lookupHash);
    Optional<RuntimeAuthUserRow> findAuthUserSnapshot(@Param("tenantId") Long tenantId, @Param("userId") Long userId);
    Optional<RuntimeAuthTenantRow> findAuthTenantSnapshot(@Param("tenantId") Long tenantId);
    List<RuntimeRouteRow> findRouteSnapshot(@Param("tenantId") Long tenantId,
                                            @Param("inboundProtocol") String inboundProtocol,
                                            @Param("tenantModelCode") String tenantModelCode);

    List<Long> findExpiredApiKeyIdsSince(@Param("from") Instant from, @Param("until") Instant until);
    List<Long> findExpiredTenantIdsSince(@Param("from") Instant from, @Param("until") Instant until);
    List<Long> findPriceChangedTenantModelIdsSince(@Param("from") Instant from, @Param("until") Instant until);
    Optional<String> findProjectionState(@Param("stateKey") String stateKey);
    void upsertProjectionState(@Param("stateKey") String stateKey, @Param("stateValue") String stateValue);
}
