package io.fluxora.platform.billing.reservation;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;

/** 预冻结报价必须与既有 CNY 原子单位规则一致，且只在总额处向上取整一次。 */
class ReservationAmountCalculatorTest {

    @Test
    void shouldQuoteAllFourTokenBucketsWithOneFinalRounding() {
        String amount = ReservationAmountCalculator.calculate(3L, 7L, 11L, 13L,
                "0.10000000", "0.30000000", "0.50000000", "0.70000000");

        assertThat(amount).isEqualTo("0.000017");
    }

    @Test
    void shouldRejectMissingPricedCacheBucketInsteadOfUnderFreezing() {
        assertThatThrownBy(() -> ReservationAmountCalculator.calculate(1L, 1L, null, 0L,
                "0.10000000", "0.30000000", "0.50000000", null))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
