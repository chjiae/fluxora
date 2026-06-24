package io.fluxora.platform.observability;

import java.util.Optional;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** 理论金额只在 Platform 重算，验证四类桶汇总后才按 CNY 原子单位取整。 */
class TheoreticalAmountCalculatorTest {

    @Test
    void shouldRoundOnceAfterSummingEveryTokenBucket() {
        Optional<String> amount = TheoreticalAmountCalculator.calculate(
                250_000L, 250_000L, 0L, 0L,
                "0.00000001", "0.00000001", null, null);

        // 两项各为 0.25 原子，汇总为 0.5 原子后向上取整为 1 原子；逐项提前取整会错误得到 2 原子。
        assertEquals(Optional.of("0.00000001"), amount);
    }

    @Test
    void missingPricedUsageMustNotCreateAFalseExactAmount() {
        Optional<String> amount = TheoreticalAmountCalculator.calculate(
                10L, 20L, null, 5L, "1", "2", "3", "4");

        assertTrue(amount.isEmpty());
    }

    @Test
    void noCachePriceMustNotRequireCacheUsage() {
        Optional<String> amount = TheoreticalAmountCalculator.calculate(
                1_000_000L, 1_000_000L, null, null, "1", "2", null, null);

        assertEquals(Optional.of("3"), amount);
    }
}
