package io.fluxora.platform.card;

import java.math.BigDecimal;
import java.time.Instant;

/**
 * 单张卡密实体。
 *
 * 安全约定：
 *   - 实体绝不持有完整明文 plaintext；plaintext 仅在批次创建响应中返回一次
 *   - card_prefix 是可见的公开标识（FLX-XXXX）
 *   - card_hash 是 HMAC-SHA256(规范化明文, card_pepper) 的 hex
 *   - 状态四态：ENABLED 可核销 / DISABLED 已停用 / REDEEMED 已核销终态 / EXPIRED 已过期
 *   - REDEEMED / EXPIRED 是终态，不允许再变更
 */
public class RechargeCard {
    private Long id;
    private Long tenantId;
    private Long batchId;
    private String cardPrefix;
    private String cardHash;
    private BigDecimal denomination;
    private String status;
    private Instant expireAt;
    private Long redeemedUserId;
    private Instant redeemedAt;
    private String disabledReason;
    private Instant createdAt;
    private Instant updatedAt;

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }
    public Long getTenantId() { return tenantId; }
    public void setTenantId(Long tenantId) { this.tenantId = tenantId; }
    public Long getBatchId() { return batchId; }
    public void setBatchId(Long batchId) { this.batchId = batchId; }
    public String getCardPrefix() { return cardPrefix; }
    public void setCardPrefix(String cardPrefix) { this.cardPrefix = cardPrefix; }
    public String getCardHash() { return cardHash; }
    public void setCardHash(String cardHash) { this.cardHash = cardHash; }
    public BigDecimal getDenomination() { return denomination; }
    public void setDenomination(BigDecimal denomination) { this.denomination = denomination; }
    public String getStatus() { return status; }
    public void setStatus(String status) { this.status = status; }
    public Instant getExpireAt() { return expireAt; }
    public void setExpireAt(Instant expireAt) { this.expireAt = expireAt; }
    public Long getRedeemedUserId() { return redeemedUserId; }
    public void setRedeemedUserId(Long redeemedUserId) { this.redeemedUserId = redeemedUserId; }
    public Instant getRedeemedAt() { return redeemedAt; }
    public void setRedeemedAt(Instant redeemedAt) { this.redeemedAt = redeemedAt; }
    public String getDisabledReason() { return disabledReason; }
    public void setDisabledReason(String disabledReason) { this.disabledReason = disabledReason; }
    public Instant getCreatedAt() { return createdAt; }
    public void setCreatedAt(Instant createdAt) { this.createdAt = createdAt; }
    public Instant getUpdatedAt() { return updatedAt; }
    public void setUpdatedAt(Instant updatedAt) { this.updatedAt = updatedAt; }
}