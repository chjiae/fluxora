package io.fluxora.platform.credit.mapper;

import java.math.BigDecimal;

/**
 * 余额调整的原子 SQL 返回结果。
 *
 * 由 {@code UPDATE … WHERE balance + delta >= 0 RETURNING balance AS balance_after,
 * balance - delta AS balance_before} 单语句产生。
 *
 * service 收到 null 视为 UPDATE 影响 0 行 → 余额不足；非 null 即原子调整成功，
 * balanceBefore / balanceAfter 来自同一语句的同一行，保证审计连贯。
 */
public record BalanceAdjustResult(BigDecimal balanceBefore, BigDecimal balanceAfter) {}
