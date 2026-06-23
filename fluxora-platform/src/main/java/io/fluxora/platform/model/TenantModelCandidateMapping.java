package io.fluxora.platform.model;

import java.time.Instant;

/**
 * 租户模型候选映射：当前租户的某个 TenantModel 可以使用本租户的某个 ProviderChannelModel。
 * 仅表达「允许使用」关系；不保存优先级、权重或协议（这些属于 RouteTarget）。
 * 服务层在写入时强制三方 tenant_id 一致：tenant_model.tenant_id == provider_channel_model.tenant_id == 入参 tenant_id。
 * 软删除时不允许直接删除被 ENABLED RouteTarget 引用的映射。
 */
public class TenantModelCandidateMapping {
    private Long id;
    /** 映射所属租户；与 tenantModel 和 channelModel 的 tenant_id 强一致。 */
    private Long tenantId;
    /** 所映射的对外模型。 */
    private Long tenantModelId;
    /** 所映射的上游候选；候选所属通道与凭证均必须可用，候选停用时不得作为 ENABLED 路由目标的支撑源。 */
    private Long providerChannelModelId;
    private boolean enabled;
    private String remark;
    /** NULL 表示未删除；非 NULL 表示已删除并记录删除时刻；删除前必须先处理或停用相关 RouteTarget。 */
    private Instant deletedAt;
    private Long createdBy;
    private Long updatedBy;
    private Instant createdAt;
    private Instant updatedAt;

    public Long getId() { return id; }
    public void setId(Long value) { id = value; }
    public Long getTenantId() { return tenantId; }
    public void setTenantId(Long value) { tenantId = value; }
    public Long getTenantModelId() { return tenantModelId; }
    public void setTenantModelId(Long value) { tenantModelId = value; }
    public Long getProviderChannelModelId() { return providerChannelModelId; }
    public void setProviderChannelModelId(Long value) { providerChannelModelId = value; }
    public boolean isEnabled() { return enabled; }
    public void setEnabled(boolean value) { enabled = value; }
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
