package io.fluxora.platform.observability;

import java.math.BigDecimal;

/** 单次聚合 SQL 产生的概要指标；null Token 表示整个范围均未知，和已知零值区分。 */
public record RelayRequestLogStats(long total, long success, long failed, long cancelled, Long inputTokens,
                                   Long outputTokens, Long cacheWriteTokens, Long cacheReadTokens,
                                   BigDecimal theoreticalAmount, long usageUnknown, long usagePartial,
                                   long pricingUnavailable, long pricingPartial) { }
