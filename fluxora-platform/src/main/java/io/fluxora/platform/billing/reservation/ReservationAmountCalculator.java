package io.fluxora.platform.billing.reservation;

import io.fluxora.platform.billing.CnyPrecisionPolicy;
import java.math.BigInteger;

/**
 * 预冻结金额报价：沿用平台唯一 CNY 原子单位策略，四个 Token 桶先汇总分子后仅向上取整一次。
 * 缓存价格存在时，对应 Token 上限缺失即拒绝，绝不以零补齐而低估冻结额。
 */
public final class ReservationAmountCalculator {
    private ReservationAmountCalculator() {
    }

    public static String calculate(Long inputTokens, Long outputTokens, Long cacheWriteTokens, Long cacheReadTokens,
                                   String inputPrice, String outputPrice, String cacheWritePrice, String cacheReadPrice) {
        requireTokens(inputTokens);
        requireTokens(outputTokens);
        if (cacheWritePrice != null) requireTokens(cacheWriteTokens);
        if (cacheReadPrice != null) requireTokens(cacheReadTokens);
        BigInteger input = CnyPrecisionPolicy.toAtoms(inputPrice);
        BigInteger output = CnyPrecisionPolicy.toAtoms(outputPrice);
        BigInteger cacheWrite = cacheWritePrice == null ? BigInteger.ZERO : CnyPrecisionPolicy.toAtoms(cacheWritePrice);
        BigInteger cacheRead = cacheReadPrice == null ? BigInteger.ZERO : CnyPrecisionPolicy.toAtoms(cacheReadPrice);
        return CnyPrecisionPolicy.formatAtoms(CnyPrecisionPolicy.quoteCharge(inputTokens, outputTokens,
                cacheWriteTokens == null ? 0L : cacheWriteTokens, cacheReadTokens == null ? 0L : cacheReadTokens,
                input, output, cacheWrite, cacheRead).finalAtoms());
    }

    private static void requireTokens(Long value) {
        if (value == null || value < 0L || value > CnyPrecisionPolicy.MAX_TOKENS_PER_REQUEST.longValueExact()) {
            throw new IllegalArgumentException("Token 上限不合法");
        }
    }
}
