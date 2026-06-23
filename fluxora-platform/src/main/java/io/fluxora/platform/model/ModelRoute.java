package io.fluxora.platform.model;

import java.time.Instant;

/**
 * 租户模型路由：表示某个 TenantModel 在某入站协议（OPENAI / ANTHROPIC）下的路由定义。
 * 同一 TenantModel 同一入站协议只允许一条未删除路由（部分唯一索引兜底）；
 * 本轮不实现真实协议转换、Adapter 或转发。
 */
public class ModelRoute {
    private Long id;
    private Long tenantId;
    private Long tenantModelId;
    /** 入站协议；RouteTarget 引用的候选通道协议必须与之兼容。 */
    private String inboundProtocol;
    private boolean enabled;
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
    public Long getTenantModelId() { return tenantModelId; }
    public void setTenantModelId(Long value) { tenantModelId = value; }
    public String getInboundProtocol() { return inboundProtocol; }
    public void setInboundProtocol(String value) { inboundProtocol = value; }
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
