package io.fluxora.platform.card.dto;

import java.math.BigDecimal;
import java.time.Instant;

/**
 * 单张卡密对外脱敏摘要。
 * 仅含 card_prefix（公开），不含 card_hash 与 plaintext。
 */
public record CardSummary(
        Long id,
        Long tenantId,
        Long batchId,
        String batchCode,
        String cardPrefix,
        BigDecimal denomination,
        String status,
        Instant expireAt,
        Long redeemedUserId,
        String redeemedUsername,
        String redeemedUserDisplayName,
        Instant redeemedAt,
        Instant createdAt
) {}