package io.fluxora.platform.runtime;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.fluxora.common.runtime.RouteExecutionEligibility;
import io.fluxora.common.security.RuntimeCredentialCipher;
import io.fluxora.platform.runtime.mapper.RuntimeAuthApiKeyRow;
import io.fluxora.platform.runtime.mapper.RuntimeAuthTenantRow;
import io.fluxora.platform.runtime.mapper.RuntimeAuthUserRow;
import io.fluxora.platform.runtime.mapper.RuntimeMapper;
import io.fluxora.platform.runtime.mapper.RuntimeRouteRow;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.TreeMap;
import java.util.LinkedHashMap;
import java.util.Map;
import org.springframework.stereotype.Component;

/**
 * 把 PostgreSQL 的一次一致读取转换为 Gateway 可直接使用的安全快照。
 * 这里是敏感字段边界：不允许 ProviderCredential、Base URL、密文或认证 Header 穿过本类。
 */
@Component
public class RuntimeSnapshotBuilder {
    public static final int SCHEMA_VERSION = 1;

    private final RuntimeMapper runtimeMapper;
    private final ObjectMapper objectMapper;
    private final RuntimeCredentialCryptoService credentialCryptoService;

    public RuntimeSnapshotBuilder(RuntimeMapper runtimeMapper, ObjectMapper objectMapper,
                                  RuntimeCredentialCryptoService credentialCryptoService) {
        this.runtimeMapper = runtimeMapper;
        this.objectMapper = objectMapper;
        this.credentialCryptoService = credentialCryptoService;
    }

    public ObjectNode build(RuntimeScope scope, long runtimeVersion, RuntimeOutboxEvent event) {
        return switch (scope.type()) {
            case AUTH_API_KEY -> apiKeySnapshot(scope, runtimeVersion, event);
            case AUTH_USER -> userSnapshot(scope, runtimeVersion, event);
            case AUTH_TENANT -> tenantSnapshot(scope, runtimeVersion, event);
            case TENANT_MODEL_ROUTE -> routeSnapshot(scope, runtimeVersion, event);
            case TENANT_MODEL_CATALOG -> catalogSnapshot(scope, runtimeVersion, event);
            case UPSTREAM_CREDENTIAL -> upstreamCredentialSnapshot(scope, runtimeVersion, event);
        };
    }

    private ObjectNode apiKeySnapshot(RuntimeScope scope, long runtimeVersion, RuntimeOutboxEvent event) {
        ObjectNode snapshot = common(scope, runtimeVersion, event);
        snapshot.put("lookupHash", scope.scopeKey());
        runtimeMapper.findAuthApiKeySnapshot(scope.scopeKey()).ifPresentOrElse(row -> writeApiKey(snapshot, row),
                () -> snapshot.put("apiKeyStatus", "MISSING").put("enabled", false));
        return snapshot;
    }

    private ObjectNode userSnapshot(RuntimeScope scope, long runtimeVersion, RuntimeOutboxEvent event) {
        ObjectNode snapshot = common(scope, runtimeVersion, event);
        String[] ids = scope.scopeKey().split(":", 2);
        long tenantId = Long.parseLong(ids[0]);
        long userId = Long.parseLong(ids[1]);
        runtimeMapper.findAuthUserSnapshot(tenantId, userId).ifPresentOrElse(row -> writeUser(snapshot, row),
                () -> snapshot.put("tenantId", tenantId).put("userId", userId)
                        .put("userStatus", "MISSING").put("enabled", false));
        return snapshot;
    }

    private ObjectNode tenantSnapshot(RuntimeScope scope, long runtimeVersion, RuntimeOutboxEvent event) {
        ObjectNode snapshot = common(scope, runtimeVersion, event);
        long tenantId = Long.parseLong(scope.scopeKey());
        runtimeMapper.findAuthTenantSnapshot(tenantId).ifPresentOrElse(row -> writeTenant(snapshot, row),
                () -> snapshot.put("tenantId", tenantId).put("tenantStatus", "MISSING").put("enabled", false));
        return snapshot;
    }

    private ObjectNode routeSnapshot(RuntimeScope scope, long runtimeVersion, RuntimeOutboxEvent event) {
        ObjectNode snapshot = common(scope, runtimeVersion, event);
        snapshot.put("tenantId", scope.tenantId());
        snapshot.put("tenantModelCode", scope.tenantModelCode());
        snapshot.put("inboundProtocol", scope.inboundProtocol());
        ArrayNode targets = snapshot.putArray("targets");
        List<RuntimeRouteRow> rows = runtimeMapper.findRouteSnapshot(scope.tenantId(), scope.inboundProtocol(), scope.tenantModelCode());
        if (rows.isEmpty()) {
            snapshot.put("tenantModelStatus", "MISSING");
            snapshot.put("routeStatus", "MISSING");
            snapshot.put("priceAvailable", false);
            return snapshot;
        }

        RuntimeRouteRow first = rows.getFirst();
        snapshot.put("tenantModelId", first.tenantModelId());
        snapshot.put("tenantModelStatus", enabledStatus(first.tenantModelEnabled(), "ENABLED", "DISABLED"));
        snapshot.put("tenantModelEnabled", first.tenantModelEnabled());
        snapshot.put("supportsStreaming", first.supportsStreaming());
        snapshot.put("defaultOutputTokens", first.defaultOutputTokens());
        snapshot.put("supportsToolCalling", first.supportsToolCalling());
        snapshot.put("supportsVision", first.supportsVision());
        snapshot.put("supportsCache", first.supportsCache());
        snapshot.put("routeId", first.routeId());
        snapshot.put("routeStatus", enabledStatus(first.routeEnabled(), "ENABLED", "DISABLED"));
        snapshot.put("routeEnabled", first.routeEnabled());
        writePrice(snapshot, first);

        Map<Long, ObjectNode> targetsById = new LinkedHashMap<>();
        Instant dispatchNow = Instant.now();
        for (RuntimeRouteRow row : rows) {
            if (row.routeTargetId() == null) {
                continue;
            }
            // 路由快照是 Gateway 的执行包，停用或软删除的关联只用于影响范围解析，不下发为候选目标。
            if (!row.targetEnabled() || !row.mappingEnabled() || !row.candidateEnabled()
                    || !row.channelEnabled() || !row.hasUsableCredential()
                    || !targetRuntimeAvailable(row, dispatchNow)) {
                continue;
            }
            ObjectNode target = targetsById.get(row.routeTargetId());
            if (target == null) {
                target = targets.addObject();
                targetsById.put(row.routeTargetId(), target);
                target.put("routeTargetId", row.routeTargetId());
                target.put("tenantModelCandidateMappingId", row.mappingId());
                target.put("providerChannelId", row.providerChannelId());
                target.put("providerChannelModelId", row.providerChannelModelId());
                target.put("priority", row.priority());
                target.put("weight", row.weight());
                target.put("targetStatus", enabledStatus(row.targetEnabled(), "ENABLED", "DISABLED"));
                target.put("mappingStatus", enabledStatus(row.mappingEnabled(), "ENABLED", "DISABLED"));
                target.put("candidateStatus", enabledStatus(row.candidateEnabled(), "ENABLED", "DISABLED"));
                target.put("channelStatus", enabledStatus(row.channelEnabled(), "ENABLED", "DISABLED"));
                target.put("outboundProtocol", row.outboundProtocol());
                target.put("upstreamModelId", row.upstreamModelId());
                target.put("baseUrl", row.normalizedBaseUrl());
                target.put("connectTimeoutMs", row.connectTimeoutMs());
                target.put("readTimeoutMs", row.readTimeoutMs());
                target.put("hasUsableCredential", row.hasUsableCredential());
                target.put("credentialPoolVersion", row.credentialPoolVersion());
                target.putArray("credentialRefs");
            }
            // 只下发当前可选的有效凭证，Gateway 稳定取首项时不会误选已停用绑定或凭证。
            if (row.providerCredentialId() != null && row.credentialBindingEnabled() && row.credentialEnabled()
                    && credentialRuntimeAvailable(row, dispatchNow)) {
                ObjectNode credentialRef = target.withArray("credentialRefs").addObject();
                credentialRef.put("providerChannelCredentialId", row.providerChannelCredentialId());
                credentialRef.put("providerCredentialId", row.providerCredentialId());
                credentialRef.put("credentialVersion", row.credentialVersion());
                credentialRef.put("authType", row.credentialAuthType());
                credentialRef.put("bindingStatus", enabledStatus(row.credentialBindingEnabled(), "ENABLED", "DISABLED"));
                credentialRef.put("credentialStatus", runtimeStatus(row.credentialRuntimeState(),
                        row.credentialCooldownUntil(), enabledStatus(row.credentialEnabled(), "ENABLED", "DISABLED"), dispatchNow));
                credentialRef.put("quotaScope", row.quotaScope());
                credentialRef.put("billingAccountGroup", row.billingAccountGroup());
                credentialRef.put("trafficWeight", row.credentialTrafficWeight() == null ? 1 : row.credentialTrafficWeight());
                credentialRef.put("maxConcurrentStreams", row.credentialMaxConcurrentStreams() == null
                        ? Integer.MAX_VALUE : row.credentialMaxConcurrentStreams());
            }
        }
        return snapshot;
    }

    /**
     * 模型目录从一次关联查询构建，且复用 RouteExecutionEligibility 与 Gateway 的真实目标筛选规则。
     * 快照严格只写 modelCode 与稳定创建秒，不下发任何路由、地址、候选或凭证元数据。
     */
    private ObjectNode catalogSnapshot(RuntimeScope scope, long runtimeVersion, RuntimeOutboxEvent event) {
        ObjectNode snapshot = common(scope, runtimeVersion, event);
        snapshot.put("tenantId", scope.tenantId());
        snapshot.put("inboundProtocol", scope.inboundProtocol());
        ArrayNode models = snapshot.putArray("models");
        List<RuntimeRouteRow> rows = runtimeMapper.findTenantModelCatalogSnapshot(scope.tenantId(), scope.inboundProtocol());
        TreeMap<String, RuntimeRouteRow> callable = new TreeMap<>();
        Instant now = Instant.now();
        for (RuntimeRouteRow row : rows) {
            if (!RouteExecutionEligibility.baseRouteCallable(row.tenantModelEnabled(), row.routeEnabled(),
                    row.priceId() != null, row.priceEffectiveAt(), row.priceExpiresAt(), now)
                    || !RouteExecutionEligibility.targetCallable(row.targetEnabled(), row.mappingEnabled(),
                    row.candidateEnabled(), row.channelEnabled(), row.hasUsableCredential(), scope.inboundProtocol(),
                    row.outboundProtocol(), row.priority(), row.weight(), row.routeTargetId(), row.providerChannelId(),
                    row.providerChannelModelId())
                    || runtimeBlocksCatalog(row)) {
                continue;
            }
            callable.putIfAbsent(row.tenantModelCode(), row);
        }
        callable.forEach((modelCode, row) -> models.addObject().put("modelCode", modelCode)
                // 迁移前异常空值以 Unix 0 作为项目统一稳定兜底，绝不使用“当前时间”。
                .put("created", row.tenantModelCreatedAt() == null ? 0L : row.tenantModelCreatedAt().getEpochSecond()));
        return snapshot;
    }

    /** 敏感 Scope 只在 Platform 投影时生成，普通路由快照永远不会经过本方法。 */
    private ObjectNode upstreamCredentialSnapshot(RuntimeScope scope, long runtimeVersion, RuntimeOutboxEvent event) {
        ObjectNode snapshot = common(scope, runtimeVersion, event);
        String[] ids = scope.scopeKey().split(":", 2);
        long tenantId = Long.parseLong(ids[0]);
        long credentialId = Long.parseLong(ids[1]);
        runtimeMapper.findRuntimeCredentialSnapshot(tenantId, credentialId).ifPresentOrElse(row -> {
            snapshot.put("tenantId", row.tenantId());
            snapshot.put("providerCredentialId", row.providerCredentialId());
            snapshot.put("credentialVersion", row.credentialVersion());
            snapshot.put("authType", row.authType());
            boolean active = row.enabled() && !row.deleted();
            snapshot.put("credentialStatus", active ? "ENABLED" : row.deleted() ? "DELETED" : "DISABLED");
            snapshot.put("enabled", active);
            if (active && !"NONE".equals(row.authType())) {
                RuntimeCredentialCipher.EncryptedPayload payload = credentialCryptoService.reencrypt(
                        row.ciphertext(), row.initializationVector(), row.encryptionVersion(), row.tenantId(),
                        row.providerCredentialId(), row.credentialVersion());
                ObjectNode encrypted = snapshot.putObject("encryptedCredentialPayload");
                encrypted.put("ciphertext", payload.ciphertext());
                encrypted.put("initializationVector", payload.initializationVector());
                encrypted.put("encryptionVersion", payload.encryptionVersion());
            }
        }, () -> snapshot.put("tenantId", tenantId).put("providerCredentialId", credentialId)
                .put("credentialStatus", "MISSING").put("enabled", false));
        return snapshot;
    }

    private ObjectNode common(RuntimeScope scope, long runtimeVersion, RuntimeOutboxEvent event) {
        ObjectNode snapshot = objectMapper.createObjectNode();
        snapshot.put("schemaVersion", SCHEMA_VERSION);
        snapshot.put("runtimeVersion", runtimeVersion);
        snapshot.put("generatedAt", Instant.now().toString());
        snapshot.put("sourceOutboxId", event.id());
        snapshot.put("sourceChangedAt", event.occurredAt().toString());
        snapshot.put("scopeType", scope.type().name());
        return snapshot;
    }

    private void writeApiKey(ObjectNode snapshot, RuntimeAuthApiKeyRow row) {
        snapshot.put("apiKeyId", row.apiKeyId());
        snapshot.put("tenantId", row.tenantId());
        snapshot.put("userId", row.userId());
        snapshot.put("lookupHash", row.lookupHash());
        snapshot.put("lookupHashVersion", row.lookupHashVersion());
        snapshot.put("enabled", row.enabled());
        snapshot.put("apiKeyStatus", row.deletedAt() != null ? "DELETED" : enabledStatus(row.enabled(), "ENABLED", "DISABLED"));
        putInstant(snapshot, "apiKeyExpiresAt", row.expireAt());
    }

    private void writeUser(ObjectNode snapshot, RuntimeAuthUserRow row) {
        snapshot.put("tenantId", row.tenantId());
        snapshot.put("userId", row.userId());
        snapshot.put("enabled", row.enabled());
        snapshot.put("userStatus", row.deletedAt() != null ? "DELETED" : enabledStatus(row.enabled(), "ENABLED", "DISABLED"));
    }

    private void writeTenant(ObjectNode snapshot, RuntimeAuthTenantRow row) {
        snapshot.put("tenantId", row.tenantId());
        snapshot.put("enabled", row.enabled());
        snapshot.put("tenantStatus", row.deletedAt() != null ? "DELETED" : enabledStatus(row.enabled(), "ENABLED", "DISABLED"));
        putInstant(snapshot, "tenantExpiresAt", row.expireAt());
        snapshot.put("settlementCurrency", row.settlementCurrencyCode());
    }

    private void writePrice(ObjectNode snapshot, RuntimeRouteRow row) {
        boolean available = row.priceId() != null;
        snapshot.put("priceAvailable", available);
        if (!available) {
            return;
        }
        snapshot.put("priceVersion", row.priceVersion());
        snapshot.put("currencyCode", row.currencyCode());
        putDecimal(snapshot, "inputPricePerMillion", row.inputPricePerMillion());
        putDecimal(snapshot, "outputPricePerMillion", row.outputPricePerMillion());
        putDecimal(snapshot, "cacheWritePricePerMillion", row.cacheWritePricePerMillion());
        putDecimal(snapshot, "cacheReadPricePerMillion", row.cacheReadPricePerMillion());
        putInstant(snapshot, "priceEffectiveAt", row.priceEffectiveAt());
        putInstant(snapshot, "priceExpiresAt", row.priceExpiresAt());
    }

    private void putDecimal(ObjectNode node, String field, BigDecimal value) {
        if (value == null) {
            node.putNull(field);
        } else {
            node.put(field, value.toPlainString());
        }
    }

    private void putInstant(ObjectNode node, String field, Instant value) {
        if (value == null) {
            node.putNull(field);
        } else {
            node.put(field, value.toString());
        }
    }

    private String enabledStatus(boolean enabled, String enabledStatus, String disabledStatus) {
        return enabled ? enabledStatus : disabledStatus;
    }

    private boolean targetRuntimeAvailable(RuntimeRouteRow row, Instant now) {
        return runtimeAvailable(row.targetRuntimeState(), row.targetCooldownUntil(), now)
                && runtimeAvailable(row.channelRuntimeState(), row.channelCooldownUntil(), now)
                && runtimeAvailable(row.candidateRuntimeState(), row.candidateCooldownUntil(), now);
    }

    private boolean credentialRuntimeAvailable(RuntimeRouteRow row, Instant now) {
        return runtimeAvailable(row.bindingRuntimeState(), row.bindingCooldownUntil(), now)
                && runtimeAvailable(row.credentialRuntimeState(), row.credentialCooldownUntil(), now)
                && runtimeAvailable(row.billingRuntimeState(), row.billingCooldownUntil(), now)
                && runtimeAvailable(row.quotaRuntimeState(), row.quotaCooldownUntil(), now);
    }

    /**
     * 调度快照尊重短 TTL 冷却；冷却到期后无需人工恢复即可重新进入候选。
     */
    private boolean runtimeAvailable(String state, Instant cooldownUntil, Instant now) {
        return state == null || "AVAILABLE".equals(state) || cooldownUntil != null && !cooldownUntil.isAfter(now);
    }

    private String runtimeStatus(String state, Instant cooldownUntil, String fallback, Instant now) {
        return runtimeAvailable(state, cooldownUntil, now) ? fallback : state;
    }

    /**
     * 模型目录只受长期不可用状态影响；RATE_LIMITED / QUARANTINED 不让模型列表闪烁。
     */
    private boolean runtimeBlocksCatalog(RuntimeRouteRow row) {
        return longTermRuntimeBlock(row.credentialRuntimeState())
                || longTermRuntimeBlock(row.billingRuntimeState())
                || longTermRuntimeBlock(row.bindingRuntimeState())
                || longTermRuntimeBlock(row.candidateRuntimeState())
                || longTermRuntimeBlock(row.targetRuntimeState());
    }

    private boolean longTermRuntimeBlock(String state) {
        return "AUTH_FAILED".equals(state) || "BILLING_EXHAUSTED".equals(state)
                || "MODEL_MAPPING_INVALID".equals(state) || "PERMISSION_DENIED".equals(state);
    }
}
