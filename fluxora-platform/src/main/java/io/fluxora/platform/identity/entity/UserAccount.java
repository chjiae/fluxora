package io.fluxora.platform.identity.entity;

import java.time.Instant;

/**
 * 用户账号实体，对应 user_account 表。
 * 支持平台级（PLATFORM）和租户级（TENANT）双作用域用户。
 * 租户级用户通过 tenantId 关联所属租户，平台级用户 tenantId 为 null。
 *
 * 软删除字段（deletedAt）：
 *   遵循 AGENT.md「软删除字段规范」——NULL 表示未删除；非 NULL 表示已被管理员
 *   软删除，账号不可登录、不可被任何受保护接口认定为有效用户。
 *   JwtAuthenticationFilter 与 AuthService 必须在每次请求时校验 deletedAt IS NULL。
 *
 * 业务状态（getStatus()）派生优先级：DELETED &gt; DISABLED &gt; ENABLED。
 */
public class UserAccount {
    /** 主键 */
    private Long id;
    /** 用户名，全局唯一（在未删除记录中唯一），用于登录认证 */
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
    /** 账号启用状态，停用后不可登录；与 deletedAt 共同决定 getStatus() */
    private boolean enabled;
    /**
     * 逻辑删除时间戳：NULL 表示未删除；非 NULL 表示已删除并记录删除时刻。
     * 由 MemberService 统一写入，Controller / Job / 外部脚本禁止直接修改。
     */
    private Instant deletedAt;
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
    public Instant getDeletedAt() { return deletedAt; }
    public void setDeletedAt(Instant deletedAt) { this.deletedAt = deletedAt; }
    /** 派生属性：是否已被软删除，用于服务层快速分支判断 */
    public boolean isDeleted() { return deletedAt != null; }
    public Instant getCreatedAt() { return createdAt; }
    public void setCreatedAt(Instant createdAt) { this.createdAt = createdAt; }
    public Instant getUpdatedAt() { return updatedAt; }
    public void setUpdatedAt(Instant updatedAt) { this.updatedAt = updatedAt; }

    /**
     * 根据 deletedAt 与 enabled 派生用户对外的业务状态。
     * 优先级：已删除 &gt; 已停用 &gt; 已启用。
     * 此方法不依赖数据库，完全由实体字段计算，确保前后端状态一致。
     * 响应 DTO 应输出 status 而非 deletedAt，避免向普通用户暴露时间戳。
     */
    public String getStatus() {
        if (deletedAt != null) return "DELETED";
        return enabled ? "ENABLED" : "DISABLED";
    }
}
