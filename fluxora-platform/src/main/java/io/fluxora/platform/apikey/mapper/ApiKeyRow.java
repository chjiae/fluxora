package io.fluxora.platform.apikey.mapper;

import java.time.Instant;

/**
 * API Key 列表/详情行投影。
 *
 * 字段包含 join 后的所属用户与租户名称，便于平台/租户管理员视角的表格直接渲染。
 * 不包含 key_hash 与任何明文片段；status 由后端派生。
 */
public class ApiKeyRow {
    private Long id;
    private Long tenantId;
    private String tenantCode;
    private String tenantName;
    private Long userId;
    private String username;
    private String userDisplayName;
    private String name;
    private String keyPrefix;
    private boolean enabled;
    private Instant expireAt;
    private Instant lastUsedAt;
    private Instant createdAt;
    private Instant updatedAt;

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }
    public Long getTenantId() { return tenantId; }
    public void setTenantId(Long tenantId) { this.tenantId = tenantId; }
    public String getTenantCode() { return tenantCode; }
    public void setTenantCode(String tenantCode) { this.tenantCode = tenantCode; }
    public String getTenantName() { return tenantName; }
    public void setTenantName(String tenantName) { this.tenantName = tenantName; }
    public Long getUserId() { return userId; }
    public void setUserId(Long userId) { this.userId = userId; }
    public String getUsername() { return username; }
    public void setUsername(String username) { this.username = username; }
    public String getUserDisplayName() { return userDisplayName; }
    public void setUserDisplayName(String userDisplayName) { this.userDisplayName = userDisplayName; }
    public String getName() { return name; }
    public void setName(String name) { this.name = name; }
    public String getKeyPrefix() { return keyPrefix; }
    public void setKeyPrefix(String keyPrefix) { this.keyPrefix = keyPrefix; }
    public boolean isEnabled() { return enabled; }
    public void setEnabled(boolean enabled) { this.enabled = enabled; }
    public Instant getExpireAt() { return expireAt; }
    public void setExpireAt(Instant expireAt) { this.expireAt = expireAt; }
    public Instant getLastUsedAt() { return lastUsedAt; }
    public void setLastUsedAt(Instant lastUsedAt) { this.lastUsedAt = lastUsedAt; }
    public Instant getCreatedAt() { return createdAt; }
    public void setCreatedAt(Instant createdAt) { this.createdAt = createdAt; }
    public Instant getUpdatedAt() { return updatedAt; }
    public void setUpdatedAt(Instant updatedAt) { this.updatedAt = updatedAt; }

    /** 派生业务状态：与 ApiKey.getStatus() 保持一致；查询已过滤 deleted_at，所以无 DELETED 分支 */
    public String getStatus() {
        if (expireAt != null && expireAt.isBefore(Instant.now())) return "EXPIRED";
        return enabled ? "ENABLED" : "DISABLED";
    }
}
