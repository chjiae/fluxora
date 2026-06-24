package io.fluxora.platform.observability;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/** 时间桶聚合结果；各 Token 的状态区分全未知、部分已知和完整已知。 */
public record RelayTrendBucket(LocalDateTime bucketStart, long requestCount, long successCount, long failedCount,
                               long cancelledCount, Long inputTokens, Long outputTokens, Long cacheWriteTokens,
                               Long cacheReadTokens, String inputTokensDataStatus, String outputTokensDataStatus,
                               String cacheWriteTokensDataStatus, String cacheReadTokensDataStatus,
                               BigDecimal calculatedTheoreticalAmount, String pricingDataStatus) { }
