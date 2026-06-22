package io.fluxora.platform.card.dto;

import java.math.BigDecimal;
import java.time.Instant;

/**
 * 卡密核销成功响应。
 *
 * 仅返回脱敏卡密前缀与本次充值金额、新余额；不返回完整明文。
 */
public record RedeemedResponse(
        Long cardId,
        String cardPrefix,
        BigDecimal amount,
        BigDecimal newBalance,
        Instant redeemedAt
) {}