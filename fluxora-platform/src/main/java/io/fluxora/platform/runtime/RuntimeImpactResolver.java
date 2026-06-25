package io.fluxora.platform.runtime;

import io.fluxora.platform.runtime.mapper.RuntimeMapper;
import io.fluxora.platform.runtime.mapper.RuntimeCredentialScopeRow;
import io.fluxora.platform.runtime.mapper.RuntimeScopeRow;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import org.springframework.stereotype.Component;

/**
 * 运行时影响范围的唯一解析器。
 * 任何控制面变更都先收敛为最小 Scope，再由 Projector 完整替换该 Scope 的快照，禁止业务层直接清缓存。
 */
@Component
public class RuntimeImpactResolver {
    private final RuntimeMapper runtimeMapper;

    public RuntimeImpactResolver(RuntimeMapper runtimeMapper) {
        this.runtimeMapper = runtimeMapper;
    }

    public Set<RuntimeScope> resolve(RuntimeOutboxEvent event) {
        String aggregateType = event.aggregateType();
        Long aggregateId = event.aggregateId();
        if ("RUNTIME_NAMESPACE".equals(aggregateType) || "FULL_REBUILD".equals(event.mutationType())) {
            return allScopes();
        }
        if (aggregateId == null) {
            return Set.of();
        }

        Set<RuntimeScope> scopes = switch (aggregateType) {
            case "API_KEY" -> runtimeMapper.findApiKeyLookupHash(aggregateId)
                    .map(hash -> Set.of(RuntimeScope.apiKey(hash, event.tenantId())))
                    .orElseGet(Set::of);
            case "USER", "USER_ACCOUNT" -> runtimeMapper.findAuthUserScope(aggregateId)
                    .map(row -> Set.of(RuntimeScope.user(row.tenantId(), aggregateId)))
                    .orElseGet(Set::of);
            case "TENANT" -> runtimeMapper.findAuthTenantScope(aggregateId)
                    .map(row -> Set.of(RuntimeScope.tenant(row.tenantId())))
                    .orElseGet(Set::of);
            case "TENANT_MODEL" -> tenantModelScopes(event, runtimeMapper.findRouteScopesByTenantModel(aggregateId));
            case "TENANT_MODEL_PRICE" -> routeScopes(runtimeMapper.findRouteScopesByTenantModel(aggregateId));
            case "MODEL_ROUTE" -> routeScopes(runtimeMapper.findRouteScopesByRoute(aggregateId));
            case "ROUTE_TARGET" -> routeScopes(runtimeMapper.findRouteScopesByTarget(aggregateId));
            case "TENANT_MODEL_CANDIDATE_MAPPING" -> routeScopes(runtimeMapper.findRouteScopesByMapping(aggregateId));
            case "PROVIDER_CHANNEL_MODEL" -> routeScopes(runtimeMapper.findRouteScopesByCandidate(aggregateId));
            case "PROVIDER_CHANNEL", "PROVIDER_CHANNEL_CREDENTIAL" -> channelScopes(aggregateId);
            case "PROVIDER_CREDENTIAL" -> credentialScopes(event, aggregateId);
            case "PROVIDER" -> routeScopes(runtimeMapper.findRouteScopesByProvider(aggregateId));
            case "PROVIDER_BASE_URL" -> routeScopes(runtimeMapper.findRouteScopesByBaseUrl(aggregateId));
            case "UPSTREAM_RUNTIME_STATE" -> event.tenantId() == null
                    ? Set.of() : routeScopes(runtimeMapper.findRouteScopesByTenant(event.tenantId()));
            default -> Set.of();
        };
        return withCatalogScopes(event, scopes);
    }

    private Set<RuntimeScope> allScopes() {
        Set<RuntimeScope> scopes = new LinkedHashSet<>();
        runtimeMapper.findAllApiKeyLookupHashes().forEach(hash -> scopes.add(RuntimeScope.apiKey(hash, null)));
        runtimeMapper.findAllAuthUserScopes().forEach(row -> scopes.add(RuntimeScope.user(row.tenantId(), row.userId())));
        runtimeMapper.findAllAuthTenantScopes().forEach(row -> scopes.add(RuntimeScope.tenant(row.tenantId())));
        scopes.addAll(routeScopes(runtimeMapper.findAllRouteScopes()));
        runtimeMapper.findAllAuthTenantScopes().forEach(row -> scopes.add(RuntimeScope.catalog(row.tenantId(), "OPENAI")));
        runtimeMapper.findAllCredentialScopes().forEach(row ->
                scopes.add(RuntimeScope.upstreamCredential(row.tenantId(), row.credentialId())));
        return scopes;
    }

    private Set<RuntimeScope> routeScopes(List<RuntimeScopeRow> rows) {
        Set<RuntimeScope> scopes = new LinkedHashSet<>();
        rows.forEach(row -> scopes.add(RuntimeScope.route(row.tenantId(), row.inboundProtocol(), row.tenantModelCode())));
        return scopes;
    }

    /**
     * 模型编码变更时，查询只能取得新编码；Outbox 保留的旧编码用于同时替换旧 Scope，
     * 防止已缓存的旧模型名继续在 Gateway L1 中存活到 TTL 结束。
     */
    private Set<RuntimeScope> tenantModelScopes(RuntimeOutboxEvent event, List<RuntimeScopeRow> rows) {
        Set<RuntimeScope> scopes = routeScopes(rows);
        String previousCode = event.impactHint();
        if (previousCode == null || previousCode.isBlank()) {
            return scopes;
        }
        rows.forEach(row -> scopes.add(RuntimeScope.route(row.tenantId(), row.inboundProtocol(), previousCode)));
        return scopes;
    }

    /** 通道凭证池变化同时失效本通道引用的路由与所有可用凭证敏感快照。 */
    private Set<RuntimeScope> channelScopes(Long channelId) {
        Set<RuntimeScope> scopes = routeScopes(runtimeMapper.findRouteScopesByChannel(channelId));
        runtimeMapper.findCredentialScopesByChannel(channelId).forEach(row ->
                scopes.add(RuntimeScope.upstreamCredential(row.tenantId(), row.credentialId())));
        return scopes;
    }

    /** 单凭证轮换、认证方式或状态变化必须同时刷新敏感 Scope 与所有引用路由。 */
    private Set<RuntimeScope> credentialScopes(RuntimeOutboxEvent event, Long credentialId) {
        Set<RuntimeScope> scopes = routeScopes(runtimeMapper.findRouteScopesByCredential(credentialId));
        runtimeMapper.findRuntimeCredentialSnapshot(event.tenantId(), credentialId)
                .ifPresent(row -> scopes.add(RuntimeScope.upstreamCredential(row.tenantId(), credentialId)));
        return scopes;
    }

    /** 目录与 OpenAI 路由同一 Outbox 变更失效；模型删除时用事件 tenantId 补齐空路由集合。 */
    private Set<RuntimeScope> withCatalogScopes(RuntimeOutboxEvent event, Set<RuntimeScope> original) {
        Set<RuntimeScope> scopes = new LinkedHashSet<>(original);
        original.stream().filter(scope -> scope.type() == RuntimeScopeType.TENANT_MODEL_ROUTE
                        && "OPENAI".equals(scope.inboundProtocol()))
                .forEach(scope -> scopes.add(RuntimeScope.catalog(scope.tenantId(), "OPENAI")));
        if ("TENANT".equals(event.aggregateType()) || "TENANT_MODEL".equals(event.aggregateType())
                || "TENANT_MODEL_PRICE".equals(event.aggregateType())) {
            if (event.tenantId() != null) scopes.add(RuntimeScope.catalog(event.tenantId(), "OPENAI"));
        }
        return scopes;
    }
}
