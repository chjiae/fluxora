package io.fluxora.platform.model;

import java.time.Instant;

/**
 * 上游模型候选：某租户在某 provider_channel 下发现或手工维护的可用上游模型。
 * V10 后不再保存 platform_model_id；候选不再映射到任何全局模型；
 * 服务层强制 tenant_id 与 provider_channel.tenant_id 一致，禁止跨租户引用。
 */
public class ProviderChannelModel {
    private Long id;
    /** 候选所属租户；与 provider_channel.tenant_id 强一致，跨租户引用一律拒绝。 */
    private Long tenantId;
    /** 候选所属通道；通道软删除后候选不应继续作为映射或路由目标支撑。 */
    private Long providerChannelId;
    /** 向具体上游传递的模型标识；同通道下未删除唯一。 */
    private String upstreamModelId;
    private String upstreamDisplayName;
    /** 候选来源：MANUAL 手工维护或 SYNCED 自动同步（本轮不实现同步）。 */
    private String sourceType;
    private boolean supportsStreaming;
    private boolean supportsToolCalling;
    private boolean supportsVision;
    private boolean supportsCache;
    private boolean enabled;
    private Instant lastSyncedAt;
    private String lastSyncSummary;
    private Instant deletedAt;
    private Long createdBy;
    private Long updatedBy;
    private Instant createdAt;
    private Instant updatedAt;

    public Long getId() { return id; }
    public void setId(Long value) { id = value; }
    public Long getTenantId() { return tenantId; }
    public void setTenantId(Long value) { tenantId = value; }
    public Long getProviderChannelId() { return providerChannelId; }
    public void setProviderChannelId(Long value) { providerChannelId = value; }
    public String getUpstreamModelId() { return upstreamModelId; }
    public void setUpstreamModelId(String value) { upstreamModelId = value; }
    public String getUpstreamDisplayName() { return upstreamDisplayName; }
    public void setUpstreamDisplayName(String value) { upstreamDisplayName = value; }
    public String getSourceType() { return sourceType; }
    public void setSourceType(String value) { sourceType = value; }
    public boolean isSupportsStreaming() { return supportsStreaming; }
    public void setSupportsStreaming(boolean value) { supportsStreaming = value; }
    public boolean isSupportsToolCalling() { return supportsToolCalling; }
    public void setSupportsToolCalling(boolean value) { supportsToolCalling = value; }
    public boolean isSupportsVision() { return supportsVision; }
    public void setSupportsVision(boolean value) { supportsVision = value; }
    public boolean isSupportsCache() { return supportsCache; }
    public void setSupportsCache(boolean value) { supportsCache = value; }
    public boolean isEnabled() { return enabled; }
    public void setEnabled(boolean value) { enabled = value; }
    public Instant getLastSyncedAt() { return lastSyncedAt; }
    public void setLastSyncedAt(Instant value) { lastSyncedAt = value; }
    public String getLastSyncSummary() { return lastSyncSummary; }
    public void setLastSyncSummary(String value) { lastSyncSummary = value; }
    public Instant getDeletedAt() { return deletedAt; }
    public void setDeletedAt(Instant value) { deletedAt = value; }
    public Long getCreatedBy() { return createdBy; }
    public void setCreatedBy(Long value) { createdBy = value; }
    public Long getUpdatedBy() { return updatedBy; }
    public void setUpdatedBy(Long value) { updatedBy = value; }
    public Instant getCreatedAt() { return createdAt; }
    public void setCreatedAt(Instant value) { createdAt = value; }
    public Instant getUpdatedAt() { return updatedAt; }
    public void setUpdatedAt(Instant value) { updatedAt = value; }
}
