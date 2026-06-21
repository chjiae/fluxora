package io.fluxora.platform.identity.entity;

import java.time.Instant;

/**
 * 角色实体，对应 role 表。
 * 通过 scopeType 实现平台与租户作用域隔离：
 * 平台角色不可分配给租户用户，租户角色不可分配给平台用户。
 */
public class Role {
    /** 主键 */
    private Long id;
    /** 角色编码，在同一作用域内唯一，如 PLATFORM_ADMIN */
    private String code;
    /** 角色名称，展示用 */
    private String name;
    /** 角色描述 */
    private String description;
    /** 作用域：PLATFORM 平台角色 / TENANT 租户角色 */
    private String scopeType;
    /** 创建时间 */
    private Instant createdAt;
    /** 最后更新时间 */
    private Instant updatedAt;

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }
    public String getCode() { return code; }
    public void setCode(String code) { this.code = code; }
    public String getName() { return name; }
    public void setName(String name) { this.name = name; }
    public String getDescription() { return description; }
    public void setDescription(String description) { this.description = description; }
    public String getScopeType() { return scopeType; }
    public void setScopeType(String scopeType) { this.scopeType = scopeType; }
    public Instant getCreatedAt() { return createdAt; }
    public void setCreatedAt(Instant createdAt) { this.createdAt = createdAt; }
    public Instant getUpdatedAt() { return updatedAt; }
    public void setUpdatedAt(Instant updatedAt) { this.updatedAt = updatedAt; }
}
