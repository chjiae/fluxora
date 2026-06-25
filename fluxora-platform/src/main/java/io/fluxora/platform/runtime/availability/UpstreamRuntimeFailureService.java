package io.fluxora.platform.runtime.availability;

import io.fluxora.common.error.BusinessErrorCode;
import io.fluxora.platform.model.ModelException;
import io.fluxora.platform.runtime.RuntimeOutboxService;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Platform 侧运行时故障消费入口。
 * 只处理 Gateway 脱敏事件，幂等写入状态并通过 Outbox 触发 Snapshot；不接收密钥、BaseUrl 或原始错误正文。
 */
@Service
public class UpstreamRuntimeFailureService {
    private final UpstreamRuntimeFailureMapper mapper;
    private final RuntimeOutboxService outboxService;

    public UpstreamRuntimeFailureService(UpstreamRuntimeFailureMapper mapper, RuntimeOutboxService outboxService) {
        this.mapper = mapper;
        this.outboxService = outboxService;
    }

    @Transactional
    public boolean consume(Map<String, String> fields) {
        RuntimeFailurePayload payload = RuntimeFailurePayload.from(fields);
        if (mapper.insertEvent(payload) == 0) return false;
        RuntimeResourceStateUpdate update = stateUpdate(payload);
        if (update != null) {
            mapper.upsertState(update);
            recordOutbox(payload);
        }
        return true;
    }

    /** 平台管理员确认修复后手动恢复 Credential 运行时状态；重复调用保持幂等。 */
    @Transactional
    public void recoverCredential(Long credentialId) {
        Long tenantId = mapper.findCredentialTenantId(credentialId)
                .orElseThrow(() -> new ModelException(BusinessErrorCode.RESOURCE_NOT_FOUND, "凭证不存在"));
        mapper.recoverState("CREDENTIAL", Long.toString(credentialId));
        outboxService.record(tenantId, "PROVIDER_CREDENTIAL", credentialId, "RUNTIME_STATE_RECOVERED", null);
    }

    private RuntimeResourceStateUpdate stateUpdate(RuntimeFailurePayload payload) {
        String scopeKey = scopeKey(payload);
        if (scopeKey == null) return null;
        String state = runtimeState(payload.failureKind());
        return new RuntimeResourceStateUpdate(payload.tenantId(), payload.failureScope(), scopeKey, state,
                payload.failureKind(), payload.occurredAt(), cooldownUntil(payload, state));
    }

    private String scopeKey(RuntimeFailurePayload payload) {
        return switch (payload.failureScope()) {
            case "CREDENTIAL" -> text(payload.credentialId());
            case "PROVIDER_CHANNEL_CREDENTIAL" -> text(payload.providerChannelCredentialId());
            case "BILLING_ACCOUNT_GROUP" -> payload.billingAccountGroup() == null ? null
                    : payload.tenantId() + ":" + payload.billingAccountGroup();
            case "QUOTA_SCOPE" -> payload.quotaScope() == null ? null : payload.tenantId() + ":" + payload.quotaScope();
            case "ROUTE_TARGET" -> text(payload.routeTargetId());
            case "PROVIDER_CHANNEL_MODEL" -> text(payload.providerChannelModelId());
            case "PROVIDER_CHANNEL" -> text(payload.providerChannelId());
            default -> null;
        };
    }

    private String runtimeState(String failureKind) {
        return switch (failureKind) {
            case "AUTH_INVALID" -> "AUTH_FAILED";
            case "UPSTREAM_BILLING_EXHAUSTED" -> "BILLING_EXHAUSTED";
            case "RATE_LIMITED" -> "RATE_LIMITED";
            case "MODEL_MAPPING_INVALID" -> "MODEL_MAPPING_INVALID";
            case "AUTH_PERMISSION_DENIED" -> "PERMISSION_DENIED";
            default -> "QUARANTINED";
        };
    }

    private Instant cooldownUntil(RuntimeFailurePayload payload, String state) {
        if ("AUTH_FAILED".equals(state) || "BILLING_EXHAUSTED".equals(state)
                || "MODEL_MAPPING_INVALID".equals(state) || "PERMISSION_DENIED".equals(state)) {
            return null;
        }
        long millis = payload.retryAfterMs() == null || payload.retryAfterMs() <= 0L ? 30_000L : payload.retryAfterMs();
        return Instant.now().plus(Duration.ofMillis(millis));
    }

    private void recordOutbox(RuntimeFailurePayload payload) {
        if (payload.credentialId() != null && "CREDENTIAL".equals(payload.failureScope())) {
            outboxService.record(payload.tenantId(), "PROVIDER_CREDENTIAL", payload.credentialId(),
                    "RUNTIME_FAILURE_STATE_CHANGED", null);
        } else if (payload.providerChannelId() != null && ("PROVIDER_CHANNEL".equals(payload.failureScope())
                || "PROVIDER_CHANNEL_CREDENTIAL".equals(payload.failureScope()))) {
            outboxService.record(payload.tenantId(), "PROVIDER_CHANNEL", payload.providerChannelId(),
                    "RUNTIME_FAILURE_STATE_CHANGED", null);
        } else if (payload.providerChannelModelId() != null && "PROVIDER_CHANNEL_MODEL".equals(payload.failureScope())) {
            outboxService.record(payload.tenantId(), "PROVIDER_CHANNEL_MODEL", payload.providerChannelModelId(),
                    "RUNTIME_FAILURE_STATE_CHANGED", null);
        } else if (payload.routeTargetId() != null && "ROUTE_TARGET".equals(payload.failureScope())) {
            outboxService.record(payload.tenantId(), "ROUTE_TARGET", payload.routeTargetId(),
                    "RUNTIME_FAILURE_STATE_CHANGED", null);
        } else {
            outboxService.record(payload.tenantId(), "UPSTREAM_RUNTIME_STATE", null,
                    "RUNTIME_FAILURE_STATE_CHANGED", null);
        }
    }

    private static String text(Long value) { return value == null ? null : value.toString(); }
}
