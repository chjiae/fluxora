package io.fluxora.platform.tenant;

import java.time.Instant;

/**
 * 租户实体，对应 tenant 表。
 *
 * 租户类型（type）：
 *   SELF_OPERATED — 自营租户，仅通过初始化流程创建，tenantCode 固定为 "default"，受后端保护不可删除/停用
 *   STANDARD      — 标准租户，通过 API 创建，可正常管理生命周期
 *
 * 软删除字段（deletedAt）：
 *   遵循 AGENT.md「软删除字段规范」——NULL 表示未删除；非 NULL 表示已删除并记录删除时刻。
 *   所有业务查询默认过滤 deletedAt IS NULL；仅认证阶段使用 findByIdIncludeDeleted 识别已删除租户。
 *
 * 租户状态（getStatus() 计算）优先级：DELETED > EXPIRED > DISABLED > ENABLED
 */
public class Tenant {
    /** 主键 */
    private Long id;
    /** 租户码，全局唯一，创建后不可修改 */
    private String tenantCode;
    /** 租户名称，对外展示 */
    private String name;
    /** 租户描述，补充说明信息 */
    private String description;
    /** 租户类型：SELF_OPERATED 自营 / STANDARD 标准 */
    private String type;
    /** 启用状态，false 时租户下全部用户不可登录 */
    private boolean enabled;
    /** 过期时间，超过后即使 enabled=true 也视为已过期 */
    private Instant expireAt;
    /**
     * 逻辑删除时间戳：NULL 表示未删除；非 NULL 表示已删除并记录删除时刻。
     * 由服务层统一写入（{@link TenantService}），禁止 Controller 或外部脚本直接修改。
     */
    private Instant deletedAt;
    /** 创建时间 */
    private Instant createdAt;
    /** 最后更新时间 */
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
    public Instant getDeletedAt() { return deletedAt; }
    public void setDeletedAt(Instant deletedAt) { this.deletedAt = deletedAt; }
    /** 派生属性：保留旧 isDeleted() 语义，便于服务层 if-deleted 分支不必每处比较 null */
    public boolean isDeleted() { return deletedAt != null; }
    public Instant getCreatedAt() { return createdAt; }
    public void setCreatedAt(Instant createdAt) { this.createdAt = createdAt; }
    public Instant getUpdatedAt() { return updatedAt; }
    public void setUpdatedAt(Instant updatedAt) { this.updatedAt = updatedAt; }

    /**
     * 根据 enabled、expire_at、deleted_at 三个字段统一计算租户对外的业务状态。
     * 优先级：已删除 > 已过期 > 已停用 > 已启用。
     * 此方法不依赖数据库，完全由实体字段计算，确保前后端状态一致。
     */
    public String getStatus() {
        if (deletedAt != null) return "DELETED";
        if (expireAt != null && expireAt.isBefore(Instant.now())) return "EXPIRED";
        return enabled ? "ENABLED" : "DISABLED";
    }
}
