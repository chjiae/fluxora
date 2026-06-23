package io.fluxora.platform.runtime;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.fluxora.platform.runtime.mapper.RuntimeAuthApiKeyRow;
import io.fluxora.platform.runtime.mapper.RuntimeAuthTenantRow;
import io.fluxora.platform.runtime.mapper.RuntimeAuthUserRow;
import io.fluxora.platform.runtime.mapper.RuntimeMapper;
import io.fluxora.platform.runtime.mapper.RuntimeRouteRow;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
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

    public RuntimeSnapshotBuilder(RuntimeMapper runtimeMapper, ObjectMapper objectMapper) {
        this.runtimeMapper = runtimeMapper;
        this.objectMapper = objectMapper;
    }

    public ObjectNode build(RuntimeScope scope, long runtimeVersion, RuntimeOutboxEvent event) {
        return switch (scope.type()) {
            case AUTH_API_KEY -> apiKeySnapshot(scope, runtimeVersion, event);
            case AUTH_USER -> userSnapshot(scope, runtimeVersion, event);
            case AUTH_TENANT -> tenantSnapshot(scope, runtimeVersion, event);
            case TENANT_MODEL_ROUTE -> routeSnapshot(scope, runtimeVersion, event);
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
        snapshot.put("supportsToolCalling", first.supportsToolCalling());
        snapshot.put("supportsVision", first.supportsVision());
        snapshot.put("supportsCache", first.supportsCache());
        snapshot.put("routeId", first.routeId());
        snapshot.put("routeStatus", enabledStatus(first.routeEnabled(), "ENABLED", "DISABLED"));
        snapshot.put("routeEnabled", first.routeEnabled());
        writePrice(snapshot, first);

        for (RuntimeRouteRow row : rows) {
            if (row.routeTargetId() == null) {
                continue;
            }
            ObjectNode target = targets.addObject();
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
            target.put("hasUsableCredential", row.hasUsableCredential());
            target.put("credentialPoolVersion", row.credentialPoolVersion());
        }
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
}
