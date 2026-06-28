package io.fluxora.platform.billing.settlement;

import java.math.BigDecimal;
import java.time.Instant;

/**
 * 模型请求直接结算事实行。
 *
 * <p>一条 requestId 只能产生一条事实记录；结算行保存价格、usage 与状态审计快照。</p>
 */
public record BillingSettlementRow(Long id, String requestId, Long tenantId, Long userId, Long apiKeyId,
                                   String currencyCode, BigDecimal actualAmount, BigDecimal outstandingAmount,
                                   String status, String reasonCode, Long tenantModelId, String tenantModelCode,
                                   String inboundProtocol, String endpoint, Integer priceVersion,
                                   BigDecimal inputPricePerMillion, BigDecimal outputPricePerMillion,
                                   BigDecimal cacheWritePricePerMillion, BigDecimal cacheReadPricePerMillion,
                                   String upstreamDispatchState, Instant finalizedAt, Instant reconciledAt,
                                   Long reconciledBy, String reconciliationNote, Instant createdAt,
                                   Instant updatedAt) {
}
