package io.fluxora.platform.identity.entity;

import java.time.Instant;

/**
 * 权限实体，对应 permission 表。
 * 权限码为稳定字符串编码，在 @PreAuthorize 注解中使用时加 PERM_ 前缀；
 * 在 JwtAuthenticationFilter 中加载为 SimpleGrantedAuthority("PERM_" + code)。
 */
public class Permission {
    /** 主键 */
    private Long id;
    /** 权限编码，全局唯一，如 TENANT_READ、TENANT_CREATE */
    private String code;
    /** 权限名称，展示用 */
    private String name;
    /** 权限描述 */
    private String description;
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
    public Instant getCreatedAt() { return createdAt; }
    public void setCreatedAt(Instant createdAt) { this.createdAt = createdAt; }
    public Instant getUpdatedAt() { return updatedAt; }
    public void setUpdatedAt(Instant updatedAt) { this.updatedAt = updatedAt; }
}
