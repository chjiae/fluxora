package io.fluxora.platform.identity.mapper;

import java.time.Instant;

/**
 * 成员列表/详情查询的扁平投影行，由 IdentityMapper.findMembers / findMemberDetail 返回。
 *
 * 字段与 user_account / user_role / role / tenant 多表连接结果对应，
 * 经服务层封装后再映射为对外 DTO（MemberSummary / MemberDetail）。
 *
 * 不包含 password_hash 等敏感字段；不暴露 deletedAt（业务查询本身已过滤）。
 */
public class MemberRow {
    private Long id;
    private String username;
    private String displayName;
    private String email;
    private boolean enabled;
    private Long tenantId;
    private String tenantCode;
    private String tenantName;
    private String roleCode;
    private String roleName;
    private Instant createdAt;
    private Instant updatedAt;

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }
    public String getUsername() { return username; }
    public void setUsername(String username) { this.username = username; }
    public String getDisplayName() { return displayName; }
    public void setDisplayName(String displayName) { this.displayName = displayName; }
    public String getEmail() { return email; }
    public void setEmail(String email) { this.email = email; }
    public boolean isEnabled() { return enabled; }
    public void setEnabled(boolean enabled) { this.enabled = enabled; }
    public Long getTenantId() { return tenantId; }
    public void setTenantId(Long tenantId) { this.tenantId = tenantId; }
    public String getTenantCode() { return tenantCode; }
    public void setTenantCode(String tenantCode) { this.tenantCode = tenantCode; }
    public String getTenantName() { return tenantName; }
    public void setTenantName(String tenantName) { this.tenantName = tenantName; }
    public String getRoleCode() { return roleCode; }
    public void setRoleCode(String roleCode) { this.roleCode = roleCode; }
    public String getRoleName() { return roleName; }
    public void setRoleName(String roleName) { this.roleName = roleName; }
    public Instant getCreatedAt() { return createdAt; }
    public void setCreatedAt(Instant createdAt) { this.createdAt = createdAt; }
    public Instant getUpdatedAt() { return updatedAt; }
    public void setUpdatedAt(Instant updatedAt) { this.updatedAt = updatedAt; }

    /**
     * 派生业务状态：DELETED 已被默认查询过滤，所以这里只判断 enabled。
     * 状态对外语义与 UserAccount.getStatus() 保持一致。
     */
    public String getStatus() {
        return enabled ? "ENABLED" : "DISABLED";
    }
}
