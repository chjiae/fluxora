package io.fluxora.platform.observability;

import java.math.BigDecimal;
import java.time.Instant;

/** 管理列表安全投影：不返回上游、凭证、正文或内部 Redis 字段。 */
public record RelayRequestLogSummary(String requestId, String tenantModelCode, String inboundProtocol, boolean stream,
                                     String requestStatus, Long durationMs, Long inputTokens, Long outputTokens,
                                     Long cacheWriteTokens, Long cacheReadTokens, String usageStatus,
                                     BigDecimal theoreticalAmount, String pricingStatus, Instant startedAt) { }
