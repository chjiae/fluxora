package io.fluxora.platform.tenant;

import java.time.Instant;

public class Tenant {
    private Long id;
    private String tenantCode;
    private String name;
    private String description;
    private String type;
    private boolean enabled;
    private Instant expireAt;
    private boolean deleted;
    private Instant createdAt;
    private Instant updatedAt;

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }
    public String getTenantCode() { return tenantCode; }
    public void setTenantCode(String tenantCode) { this.tenantCode = tenantCode; }
    public String getName() { return name; }
    public void setName(String name) { this.name = name; }
    public String getDescription() { return description; }
    public void setDescription(String description) { this.description = description; }
    public String getType() { return type; }
    public void setType(String type) { this.type = type; }
    public boolean isEnabled() { return enabled; }
    public void setEnabled(boolean enabled) { this.enabled = enabled; }
    public Instant getExpireAt() { return expireAt; }
    public void setExpireAt(Instant expireAt) { this.expireAt = expireAt; }
    public boolean isDeleted() { return deleted; }
    public void setDeleted(boolean deleted) { this.deleted = deleted; }
    public Instant getCreatedAt() { return createdAt; }
    public void setCreatedAt(Instant createdAt) { this.createdAt = createdAt; }
    public Instant getUpdatedAt() { return updatedAt; }
    public void setUpdatedAt(Instant updatedAt) { this.updatedAt = updatedAt; }

    /**
     * 根据 enabled、expire_at、is_deleted 三个字段统一计算租户对外的业务状态。
     * 优先级：已删除 > 已过期 > 已停用 > 已启用。
     * 此方法不依赖数据库，完全由实体字段计算，确保前后端状态一致。
     */
    public String getStatus() {
        if (deleted) return "DELETED";
        if (expireAt != null && expireAt.isBefore(Instant.now())) return "EXPIRED";
        return enabled ? "ENABLED" : "DISABLED";
    }
}
