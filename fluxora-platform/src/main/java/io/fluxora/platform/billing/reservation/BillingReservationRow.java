package io.fluxora.platform.billing.reservation;

import java.math.BigDecimal;
import java.time.Instant;

/** 预冻结数据库行，只在 Platform 服务层使用，严禁直接作为外部用户响应。 */
public record BillingReservationRow(Long id, String requestId, String requestFingerprint, Long tenantId, Long userId,
                                    Long apiKeyId, Long walletId, String currencyCode, BigDecimal reservationAmount,
                                    BigDecimal actualAmount, BigDecimal settledAmount, BigDecimal releasedAmount,
                                    BigDecimal outstandingAmount, String status, String reasonCode, Long tenantModelId,
                                    String tenantModelCode, String inboundProtocol, String endpoint, Integer priceVersion,
                                    BigDecimal inputPricePerMillion, BigDecimal outputPricePerMillion,
                                    BigDecimal cacheWritePricePerMillion, BigDecimal cacheReadPricePerMillion,
                                    Long inputTokenCeiling, Long outputTokenCeiling, Long cacheWriteTokenCeiling,
                                    Long cacheReadTokenCeiling, String upstreamDispatchState, Instant reservedAt,
                                    Instant finalizedAt, Instant reconciledAt, Long reconciledBy, String reconciliationNote,
                                    Instant createdAt, Instant updatedAt) {
}
