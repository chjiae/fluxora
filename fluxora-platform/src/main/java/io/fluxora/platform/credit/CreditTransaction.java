package io.fluxora.platform.credit;

import java.math.BigDecimal;
import java.time.Instant;

/**
 * 额度流水实体，对应 credit_transaction 表。
 *
 * 规则：
 *   - 仅 INSERT；后端不提供 UPDATE / DELETE SQL；
 *   - direction ∈ {CREDIT, DEBIT}；delta 始终为正数（方向由 direction 表达）；
 *   - balanceBefore / balanceAfter 来自同事务的原子 UPDATE…RETURNING，保证审计连贯。
 */
public class CreditTransaction {
    private Long id;
    private Long tenantId;
    private Long userId;
    private String direction;
    private BigDecimal delta;
    private BigDecimal balanceBefore;
    private BigDecimal balanceAfter;
    private String reason;
    private Long operatorId;
    private String operatorName;
    private Instant createdAt;

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }
    public Long getTenantId() { return tenantId; }
    public void setTenantId(Long tenantId) { this.tenantId = tenantId; }
    public Long getUserId() { return userId; }
    public void setUserId(Long userId) { this.userId = userId; }
    public String getDirection() { return direction; }
    public void setDirection(String direction) { this.direction = direction; }
    public BigDecimal getDelta() { return delta; }
    public void setDelta(BigDecimal delta) { this.delta = delta; }
    public BigDecimal getBalanceBefore() { return balanceBefore; }
    public void setBalanceBefore(BigDecimal balanceBefore) { this.balanceBefore = balanceBefore; }
    public BigDecimal getBalanceAfter() { return balanceAfter; }
    public void setBalanceAfter(BigDecimal balanceAfter) { this.balanceAfter = balanceAfter; }
    public String getReason() { return reason; }
    public void setReason(String reason) { this.reason = reason; }
    public Long getOperatorId() { return operatorId; }
    public void setOperatorId(Long operatorId) { this.operatorId = operatorId; }
    public String getOperatorName() { return operatorName; }
    public void setOperatorName(String operatorName) { this.operatorName = operatorName; }
    public Instant getCreatedAt() { return createdAt; }
    public void setCreatedAt(Instant createdAt) { this.createdAt = createdAt; }
}