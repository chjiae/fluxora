package io.fluxora.platform.upstream.provider;

import java.time.Instant;

/** 上游厂商实体：共享资源 tenantId 为空，私有资源必须绑定唯一租户。 */
public class Provider {
    private Long id;
    private String name;
    private String code;
    private String scopeType;
    private Long tenantId;
    private String description;
    private boolean enabled;
    private Instant deletedAt;
    private Instant createdAt;
    private Instant updatedAt;
    public Long getId() { return id; } public void setId(Long id) { this.id = id; }
    public String getName() { return name; } public void setName(String name) { this.name = name; }
    public String getCode() { return code; } public void setCode(String code) { this.code = code; }
    public String getScopeType() { return scopeType; } public void setScopeType(String scopeType) { this.scopeType = scopeType; }
    public Long getTenantId() { return tenantId; } public void setTenantId(Long tenantId) { this.tenantId = tenantId; }
    public String getDescription() { return description; } public void setDescription(String description) { this.description = description; }
    public boolean isEnabled() { return enabled; } public void setEnabled(boolean enabled) { this.enabled = enabled; }
    public Instant getDeletedAt() { return deletedAt; } public void setDeletedAt(Instant deletedAt) { this.deletedAt = deletedAt; }
    public Instant getCreatedAt() { return createdAt; } public void setCreatedAt(Instant createdAt) { this.createdAt = createdAt; }
    public Instant getUpdatedAt() { return updatedAt; } public void setUpdatedAt(Instant updatedAt) { this.updatedAt = updatedAt; }
}
