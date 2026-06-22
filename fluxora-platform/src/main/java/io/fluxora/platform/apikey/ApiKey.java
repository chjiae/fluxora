package io.fluxora.platform.apikey;

import java.time.Instant;

/**
 * API Key 实体，对应 api_key 表。
 *
 * 安全约定：
 *   - 实体绝不持有完整明文 Key；plaintext 仅在 {@link io.fluxora.platform.apikey.dto.CreatedApiKeyResponse}
 *     中由创建接口返回一次。
 *   - key_prefix 是可见的公开标识；key_hash 是 HMAC-SHA256(secret_part, server_pepper) 的 hex。
 *   - 状态四态由 enabled / expireAt / deletedAt 三个字段派生（{@link #getStatus()}），
 *     遵循 AGENT.md 软删除规范（deleted_at TIMESTAMPTZ NULL）。
 */
public class ApiKey {
    /** 主键 */
    private Long id;
    /** 所属租户 */
    private Long tenantId;
    /** 所属租户用户 */
    private Long userId;
    /** Key 名称 */
    private String name;
    /** Key 前缀，形如 flx_XXXXXXXX，DB 索引列 */
    private String keyPrefix;
    /** HMAC-SHA256(secret_part, server_pepper) 的 hex；网关校验用 */
    private String keyHash;
    /** 启用状态 */
    private boolean enabled;
    /** 过期时间；NULL 表示永不过期 */
    private Instant expireAt;
    /** 最后使用时间；预留字段，本轮始终为 NULL */
    private Instant lastUsedAt;
    /** 软删除时间戳；NULL 表示未删除 */
    private Instant deletedAt;
    private Instant createdAt;
    private Instant updatedAt;

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }
    public Long getTenantId() { return tenantId; }
    public void setTenantId(Long tenantId) { this.tenantId = tenantId; }
    public Long getUserId() { return userId; }
    public void setUserId(Long userId) { this.userId = userId; }
    public String getName() { return name; }
    public void setName(String name) { this.name = name; }
    public String getKeyPrefix() { return keyPrefix; }
    public void setKeyPrefix(String keyPrefix) { this.keyPrefix = keyPrefix; }
    public String getKeyHash() { return keyHash; }
    public void setKeyHash(String keyHash) { this.keyHash = keyHash; }
    public boolean isEnabled() { return enabled; }
    public void setEnabled(boolean enabled) { this.enabled = enabled; }
    public Instant getExpireAt() { return expireAt; }
    public void setExpireAt(Instant expireAt) { this.expireAt = expireAt; }
    public Instant getLastUsedAt() { return lastUsedAt; }
    public void setLastUsedAt(Instant lastUsedAt) { this.lastUsedAt = lastUsedAt; }
    public Instant getDeletedAt() { return deletedAt; }
    public void setDeletedAt(Instant deletedAt) { this.deletedAt = deletedAt; }
    public boolean isDeleted() { return deletedAt != null; }
    public Instant getCreatedAt() { return createdAt; }
    public void setCreatedAt(Instant createdAt) { this.createdAt = createdAt; }
    public Instant getUpdatedAt() { return updatedAt; }
    public void setUpdatedAt(Instant updatedAt) { this.updatedAt = updatedAt; }

    /**
     * 派生业务状态：DELETED > EXPIRED > DISABLED > ENABLED。
     * 用于响应 DTO；不入库；与租户 / 用户的 getStatus() 风格一致。
     */
    public String getStatus() {
        if (deletedAt != null) return "DELETED";
        if (expireAt != null && expireAt.isBefore(Instant.now())) return "EXPIRED";
        return enabled ? "ENABLED" : "DISABLED";
    }
}
