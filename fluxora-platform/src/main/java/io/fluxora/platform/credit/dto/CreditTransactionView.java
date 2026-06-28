package io.fluxora.platform.credit.dto;

import java.math.BigDecimal;
import java.time.Instant;

/** 流水对外视图；amount = delta（始终为正），方向由 direction 表达 */
public record CreditTransactionView(
        Long id,
        Long tenantId,
        String tenantCode,
        String tenantName,
        Long userId,
        String username,
        String userDisplayName,
        String direction,
        BigDecimal amount,
        BigDecimal balanceBefore,
        BigDecimal balanceAfter,
        String transactionType,
        Long billingSettlementId,
        String reason,
        Long operatorId,
        String operatorName,
        Instant createdAt
) {}
