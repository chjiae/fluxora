package io.fluxora.platform.card;

import java.math.BigDecimal;
import java.time.Instant;

/**
 * 卡密批次实体。
 *
 * 业务约束：
 *   - status 仅取 ENABLED / DISABLED；REDEEMED / EXPIRED 是单张卡密的状态
 *   - denomination 与 user_credit_account.balance 同精度（DECIMAL(20,4)）
 *   - total_count 受 fluxora.security.card.batch-max-count 上限约束
 *   - 统计字段（usedCount、availableCount 等）通过聚合 SQL 派生，不在实体冗余
 */
public class RechargeCardBatch {
    private Long id;
    private Long tenantId;
    private String batchCode;
    private String name;
    private BigDecimal denomination;
    private Integer totalCount;
    private String status;
    private Instant expireAt;
    private Long createdById;
    private Instant createdAt;
    private Instant updatedAt;

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }
    public Long getTenantId() { return tenantId; }
    public void setTenantId(Long tenantId) { this.tenantId = tenantId; }
    public String getBatchCode() { return batchCode; }
    public void setBatchCode(String batchCode) { this.batchCode = batchCode; }
    public String getName() { return name; }
    public void setName(String name) { this.name = name; }
    public BigDecimal getDenomination() { return denomination; }
    public void setDenomination(BigDecimal denomination) { this.denomination = denomination; }
    public Integer getTotalCount() { return totalCount; }
    public void setTotalCount(Integer totalCount) { this.totalCount = totalCount; }
    public String getStatus() { return status; }
    public void setStatus(String status) { this.status = status; }
    public Instant getExpireAt() { return expireAt; }
    public void setExpireAt(Instant expireAt) { this.expireAt = expireAt; }
    public Long getCreatedById() { return createdById; }
    public void setCreatedById(Long createdById) { this.createdById = createdById; }
    public Instant getCreatedAt() { return createdAt; }
    public void setCreatedAt(Instant createdAt) { this.createdAt = createdAt; }
    public Instant getUpdatedAt() { return updatedAt; }
    public void setUpdatedAt(Instant updatedAt) { this.updatedAt = updatedAt; }
}