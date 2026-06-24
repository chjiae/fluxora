package io.fluxora.platform.observability;

import io.fluxora.platform.billing.CnyPrecisionPolicy;
import java.math.BigInteger;
import java.util.Optional;

/**
 * Platform 以 Gateway 事件中的不可变价格快照重算理论金额；绝不回查当前模型价格。
 * 所有四类 Token 分子先汇总，随后调用统一 CNY 策略只做一次原子单位向上取整。
 */
public final class TheoreticalAmountCalculator {
    private TheoreticalAmountCalculator() {
    }

    public static Optional<String> calculate(Long inputTokens, Long outputTokens, Long cacheWriteTokens,
                                             Long cacheReadTokens, String inputPricePerMillion,
                                             String outputPricePerMillion, String cacheWritePricePerMillion,
                                             String cacheReadPricePerMillion) {
        if (!validTokens(inputTokens) || !validTokens(outputTokens)
                || inputPricePerMillion == null || outputPricePerMillion == null
                || cacheWritePricePerMillion != null && !validTokens(cacheWriteTokens)
                || cacheReadPricePerMillion != null && !validTokens(cacheReadTokens)) {
            return Optional.empty();
        }
        try {
            BigInteger inputPrice = CnyPrecisionPolicy.toAtoms(inputPricePerMillion);
            BigInteger outputPrice = CnyPrecisionPolicy.toAtoms(outputPricePerMillion);
            BigInteger cacheWritePrice = cacheWritePricePerMillion == null ? BigInteger.ZERO
                    : CnyPrecisionPolicy.toAtoms(cacheWritePricePerMillion);
            BigInteger cacheReadPrice = cacheReadPricePerMillion == null ? BigInteger.ZERO
                    : CnyPrecisionPolicy.toAtoms(cacheReadPricePerMillion);
            CnyPrecisionPolicy.ChargeQuote quote = CnyPrecisionPolicy.quoteCharge(inputTokens, outputTokens,
                    cacheWriteTokens == null ? 0L : cacheWriteTokens,
                    cacheReadTokens == null ? 0L : cacheReadTokens,
                    inputPrice, outputPrice, cacheWritePrice, cacheReadPrice);
            return Optional.of(CnyPrecisionPolicy.formatAtoms(quote.finalAtoms()));
        } catch (IllegalArgumentException ex) {
            // 外部 Stream 事件字段不可信；异常事件只能留待重试/排查，不能写入伪造金额。
            return Optional.empty();
        }
    }

    private static boolean validTokens(Long tokens) {
        return tokens != null && tokens >= 0L;
    }
}
