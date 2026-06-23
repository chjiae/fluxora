package io.fluxora.platform.model;

import java.time.Instant;

/**
 * 路由目标：以 tenant_model_candidate_mapping 作为事实来源，承载优先级与权重。
 * V10 后不再直接引用 provider_channel_model_id；provider_channel_id 与 upstream_model_id_snapshot
 * 仅作为审计冗余，写入和合法性校验仍以 mapping 为准。
 * 服务层在写入时强制四方一致：modelRoute.tenant_id == mapping.tenant_id == candidate.tenant_id == channel.tenant_id。
 */
public class RouteTarget {
    private Long id;
    private Long tenantId;
    private Long modelRouteId;
    /** 事实来源：合法性校验、能力支撑判定、租户隔离全部以此为准。 */
    private Long tenantModelCandidateMappingId;
    /** 审计冗余：候选映射对应通道的 id，仅用于查询展示与未来运行时快照。 */
    private Long providerChannelId;
    /** 审计冗余：创建时复制的上游模型标识，便于未来排查与快照重建。 */
    private String upstreamModelIdSnapshot;
    private boolean enabled;
    /** 同路由内调度优先级；本轮仅保存配置，不参与真实调度。 */
    private int priority;
    /** 同优先级分流权重；本轮仅保存配置，不参与真实调度。 */
    private int weight;
    private String remark;
    private Instant deletedAt;
    private Long createdBy;
    private Long updatedBy;
    private Instant createdAt;
    private Instant updatedAt;

    public Long getId() { return id; }
    public void setId(Long value) { id = value; }
    public Long getTenantId() { return tenantId; }
    public void setTenantId(Long value) { tenantId = value; }
    public Long getModelRouteId() { return modelRouteId; }
    public void setModelRouteId(Long value) { modelRouteId = value; }
    public Long getTenantModelCandidateMappingId() { return tenantModelCandidateMappingId; }
    public void setTenantModelCandidateMappingId(Long value) { tenantModelCandidateMappingId = value; }
    public Long getProviderChannelId() { return providerChannelId; }
    public void setProviderChannelId(Long value) { providerChannelId = value; }
    public String getUpstreamModelIdSnapshot() { return upstreamModelIdSnapshot; }
    public void setUpstreamModelIdSnapshot(String value) { upstreamModelIdSnapshot = value; }
    public boolean isEnabled() { return enabled; }
    public void setEnabled(boolean value) { enabled = value; }
    public int getPriority() { return priority; }
    public void setPriority(int value) { priority = value; }
    public int getWeight() { return weight; }
    public void setWeight(int value) { weight = value; }
    public String getRemark() { return remark; }
    public void setRemark(String value) { remark = value; }
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
