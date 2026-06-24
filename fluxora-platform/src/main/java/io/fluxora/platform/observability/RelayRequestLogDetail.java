package io.fluxora.platform.observability;

import java.math.BigDecimal;
import java.time.Instant;

/** 详情同样严格限制为安全审计元数据，不含请求内容、响应内容或上游配置。 */
public record RelayRequestLogDetail(String requestId, String tenantModelCode, String inboundProtocol,
                                    String outboundProtocol, String endpoint, boolean stream, String requestStatus,
                                    String errorCategory, Integer safeHttpStatus, Instant startedAt, Instant finishedAt,
                                    Long durationMs, Long inputTokens, Long outputTokens, Long cacheWriteTokens,
                                    Long cacheReadTokens, String usageStatus, String currencyCode, Integer priceVersion,
                                    BigDecimal inputPricePerMillion, BigDecimal outputPricePerMillion,
                                    BigDecimal cacheWritePricePerMillion, BigDecimal cacheReadPricePerMillion,
                                    BigDecimal theoreticalAmount, String pricingStatus) { }
