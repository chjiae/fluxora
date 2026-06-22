package io.fluxora.platform.upstream.provider.mapper;

import java.time.Instant;

/**
 * 厂商列表关联投影。
 * tenantName 来自 LEFT JOIN tenant，仅用于平台管理员跨租户识别归属；私有厂商对归属租户可见。
 * 不选择 deleted_at 列；getStatus 依据 enabled 派生（查询已过滤删除行）。
 */
public class ProviderRow {
    private Long id;
    private String name;
    private String code;
    private String scopeType;
    private Long tenantId;
    private String tenantName;
    private String description;
    private boolean enabled;
    private Instant createdAt;
    private Instant updatedAt;

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }
    public String getName() { return name; }
    public void setName(String name) { this.name = name; }
    public String getCode() { return code; }
    public void setCode(String code) { this.code = code; }
    public String getScopeType() { return scopeType; }
    public void setScopeType(String scopeType) { this.scopeType = scopeType; }
    public Long getTenantId() { return tenantId; }
    public void setTenantId(Long tenantId) { this.tenantId = tenantId; }
    public String getTenantName() { return tenantName; }
    public void setTenantName(String tenantName) { this.tenantName = tenantName; }
    public String getDescription() { return description; }
    public void setDescription(String description) { this.description = description; }
    public boolean isEnabled() { return enabled; }
    public void setEnabled(boolean enabled) { this.enabled = enabled; }
    public Instant getCreatedAt() { return createdAt; }
    public void setCreatedAt(Instant createdAt) { this.createdAt = createdAt; }
    public Instant getUpdatedAt() { return updatedAt; }
    public void setUpdatedAt(Instant updatedAt) { this.updatedAt = updatedAt; }

    /** 列表查询已过滤删除行，此处仅需区分启用/停用。 */
    public String getStatus() { return enabled ? "ENABLED" : "DISABLED"; }
}
