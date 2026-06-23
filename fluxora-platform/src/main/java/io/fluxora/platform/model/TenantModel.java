package io.fluxora.platform.model;

import java.time.Instant;

/**
 * 租户对外模型（唯一对外模型主体）。
 * V10 移除了全局 PlatformModel 体系，本类不再继承、引用或映射任何平台模型；
 * 模型的能力、价格、候选映射、路由全部归属当前租户，跨租户完全隔离。
 * model_code 仅要求同租户内唯一；不同租户允许使用相同 model_code。
 */
public class TenantModel {
    private Long id;
    /** 模型所属租户；服务层强制使用 JWT 当前租户或平台管理员显式指定的目标租户，永不信任前端入参。 */
    private Long tenantId;
    /** 对外稳定模型编码；同租户内 model_code 唯一（部分唯一索引兜底）；软删除后允许复用。 */
    private String modelCode;
    private String displayName;
    private String description;
    /** 租户声明对外支持流式输出；启用前必须有至少一个有效候选映射支撑该能力。 */
    private boolean supportsStreaming;
    /** 租户声明对外支持工具调用；启用前必须有至少一个有效候选映射支撑该能力。 */
    private boolean supportsToolCalling;
    /** 租户声明对外支持视觉输入；启用前必须有至少一个有效候选映射支撑该能力。 */
    private boolean supportsVision;
    /** 租户声明对外支持缓存命中；启用前候选必须支撑缓存，且缓存读写价格不能为空。 */
    private boolean supportsCache;
    /**
     * 发布状态：DRAFT 未完成配置；ENABLED 对 C 端可见；DISABLED 暂停展示。
     * 与 enabled 字段共同决定 getStatus() 三态展示，保持 DRAFT > DISABLED > ENABLED 的优先级。
     */
    private String publishStatus;
    /** 冗余启用标记：与 publish_status=ENABLED 同步；用于公开目录索引的快速过滤。 */
    private boolean enabled;
    /** NULL 表示未删除；非 NULL 表示已删除并记录删除时刻，用于审计、未来窗口期恢复与排查。 */
    private Instant deletedAt;
    private Long createdBy;
    private Long updatedBy;
    private Instant createdAt;
    private Instant updatedAt;

    public Long getId() { return id; }
    public void setId(Long value) { id = value; }
    public Long getTenantId() { return tenantId; }
    public void setTenantId(Long value) { tenantId = value; }
    public String getModelCode() { return modelCode; }
    public void setModelCode(String value) { modelCode = value; }
    public String getDisplayName() { return displayName; }
    public void setDisplayName(String value) { displayName = value; }
    public String getDescription() { return description; }
    public void setDescription(String value) { description = value; }
    public boolean isSupportsStreaming() { return supportsStreaming; }
    public void setSupportsStreaming(boolean value) { supportsStreaming = value; }
    public boolean isSupportsToolCalling() { return supportsToolCalling; }
    public void setSupportsToolCalling(boolean value) { supportsToolCalling = value; }
    public boolean isSupportsVision() { return supportsVision; }
    public void setSupportsVision(boolean value) { supportsVision = value; }
    public boolean isSupportsCache() { return supportsCache; }
    public void setSupportsCache(boolean value) { supportsCache = value; }
    public String getPublishStatus() { return publishStatus; }
    public void setPublishStatus(String value) { publishStatus = value; }
    public boolean isEnabled() { return enabled; }
    public void setEnabled(boolean value) { enabled = value; }
    public Instant getDeletedAt() { return deletedAt; }
    public void setDeletedAt(Instant value) { deletedAt = value; }
    public Long getCreatedBy() { return createdBy; }
    public void setCreatedBy(Long value) { createdBy = value; }
    public Long getUpdatedBy() { return updatedBy; }
    public void setUpdatedBy(Long value) { updatedBy = value; }
    public Instant getCreatedAt() { return createdAt; }
    public void setCreatedAt(Instant value) { createdAt = value; }
    public Instant getUpdatedAt() { return updatedAt; }
    public void setUpdatedAt(Instant value) { updatedAt = value; }
}
