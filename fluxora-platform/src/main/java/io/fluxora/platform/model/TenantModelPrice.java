package io.fluxora.platform.model;

import java.math.BigDecimal;
import java.time.Instant;

/**
 * 租户模型价格历史；金额以「每 100 万 Token」的 CNY 8 位小数精度存储。
 * V10 后所有价格只属于 TenantModel；不存在平台默认价、价格继承或跨租户价格复用。
 * 改价新增版本而非覆盖；同一 tenantModel 同一时刻只允许一个 expired_at IS NULL 的有效版本。
 */
public class TenantModelPrice {
    private Long id;
    /** 价格所属租户；与 tenantModel.tenant_id 强一致。 */
    private Long tenantId;
    private Long tenantModelId;
    /** 当前固定 CNY；保留字段用于未来多币种价格历史扩展。 */
    private String currencyCode;
    /** 输入单价：每 100 万 Token，CNY 8 位小数；接口必须以字符串传输，禁止 float/double。 */
    private BigDecimal inputPricePerMillion;
    /** 输出单价：每 100 万 Token，CNY 8 位小数；接口必须以字符串传输，禁止 float/double。 */
    private BigDecimal outputPricePerMillion;
    /** 缓存写入单价；不支持缓存的模型可为 NULL。 */
    private BigDecimal cacheWritePricePerMillion;
    /** 缓存读取单价；不支持缓存的模型可为 NULL。 */
    private BigDecimal cacheReadPricePerMillion;
    /** 同一租户模型内的价格版本号，服务层在事务内单调递增。 */
    private int version;
    private Instant effectiveAt;
    /** NULL 表示当前有效版本；部分唯一索引兜底每模型仅一个有效价格。 */
    private Instant expiredAt;
    private Long createdBy;
    private Instant createdAt;

    public Long getId() { return id; }
    public void setId(Long value) { id = value; }
    public Long getTenantId() { return tenantId; }
    public void setTenantId(Long value) { tenantId = value; }
    public Long getTenantModelId() { return tenantModelId; }
    public void setTenantModelId(Long value) { tenantModelId = value; }
    public String getCurrencyCode() { return currencyCode; }
    public void setCurrencyCode(String value) { currencyCode = value; }
    public BigDecimal getInputPricePerMillion() { return inputPricePerMillion; }
    public void setInputPricePerMillion(BigDecimal value) { inputPricePerMillion = value; }
    public BigDecimal getOutputPricePerMillion() { return outputPricePerMillion; }
    public void setOutputPricePerMillion(BigDecimal value) { outputPricePerMillion = value; }
    public BigDecimal getCacheWritePricePerMillion() { return cacheWritePricePerMillion; }
    public void setCacheWritePricePerMillion(BigDecimal value) { cacheWritePricePerMillion = value; }
    public BigDecimal getCacheReadPricePerMillion() { return cacheReadPricePerMillion; }
    public void setCacheReadPricePerMillion(BigDecimal value) { cacheReadPricePerMillion = value; }
    public int getVersion() { return version; }
    public void setVersion(int value) { version = value; }
    public Instant getEffectiveAt() { return effectiveAt; }
    public void setEffectiveAt(Instant value) { effectiveAt = value; }
    public Instant getExpiredAt() { return expiredAt; }
    public void setExpiredAt(Instant value) { expiredAt = value; }
    public Long getCreatedBy() { return createdBy; }
    public void setCreatedBy(Long value) { createdBy = value; }
    public Instant getCreatedAt() { return createdAt; }
    public void setCreatedAt(Instant value) { createdAt = value; }
}
