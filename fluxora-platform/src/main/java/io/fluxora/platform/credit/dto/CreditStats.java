package io.fluxora.platform.credit.dto;

import java.math.BigDecimal;

/**
 * 额度聚合统计（指标条用）。
 *   totalAccounts  —— 范围内账户数
 *   totalBalance   —— 余额合计
 *   totalCredits   —— 累计增加额度（CREDIT 流水之和）
 *   totalDebits    —— 累计扣减额度（DEBIT 流水之和）
 */
public record CreditStats(
        long totalAccounts,
        BigDecimal totalBalance,
        BigDecimal totalFrozenBalance,
        BigDecimal totalCredits,
        BigDecimal totalDebits,
        long transactionCount
) {}
