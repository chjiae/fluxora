package io.fluxora.platform.identity.entity;

import java.time.Instant;

/**
 * 用户账号实体，对应 user_account 表。
 * 支持平台级（PLATFORM）和租户级（TENANT）双作用域用户。
 * 租户级用户通过 tenantId 关联所属租户，平台级用户 tenantId 为 null。
 */
public class UserAccount {
    /** 主键 */
    private Long id;
    /** 用户名，全局唯一，用于登录认证 */
    private String username;
    /** BCrypt 哈希密码，绝不以明文存储或传输 */
    private String passwordHash;
    /** 显示名称，界面展示用 */
    private String displayName;
    /** 邮箱地址，可选 */
    private String email;
    /** 作用域：PLATFORM 平台级 / TENANT 租户级，决定菜单和权限范围 */
    private String scopeType;
    /** 所属租户 ID，仅租户级用户有值 */
    private Long tenantId;
    /** 账号启用状态，停用后不可登录 */
    private boolean enabled;
    /** 创建时间 */
    private Instant createdAt;
    /** 最后更新时间 */
    private Instant updatedAt;

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }
    public String getUsername() { return username; }
    public void setUsername(String username) { this.username = username; }
    public String getPasswordHash() { return passwordHash; }
    public void setPasswordHash(String passwordHash) { this.passwordHash = passwordHash; }
    public String getDisplayName() { return displayName; }
    public void setDisplayName(String displayName) { this.displayName = displayName; }
    public String getEmail() { return email; }
    public void setEmail(String email) { this.email = email; }
    public String getScopeType() { return scopeType; }
    public void setScopeType(String scopeType) { this.scopeType = scopeType; }
    public Long getTenantId() { return tenantId; }
    public void setTenantId(Long tenantId) { this.tenantId = tenantId; }
    public boolean isEnabled() { return enabled; }
    public void setEnabled(boolean enabled) { this.enabled = enabled; }
    public Instant getCreatedAt() { return createdAt; }
    public void setCreatedAt(Instant createdAt) { this.createdAt = createdAt; }
    public Instant getUpdatedAt() { return updatedAt; }
    public void setUpdatedAt(Instant updatedAt) { this.updatedAt = updatedAt; }
}
