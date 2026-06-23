package io.fluxora.platform.billing;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.math.BigInteger;
import org.junit.jupiter.api.Test;

/** 账务与模型价格统一精度规则：先汇总，再按一次规则向上取整。 */
class CnyPrecisionPolicyTest {
    @Test void convertsDisplayAmountToEightDecimalAtomicUnitsWithoutBinaryFloatingPoint() {
        assertThat(CnyPrecisionPolicy.toAtoms("0.00000001")).isEqualTo(BigInteger.ONE);
        assertThat(CnyPrecisionPolicy.formatAtoms(new BigInteger("123456789"))).isEqualTo("1.23456789");
    }
    @Test void rejectsScientificNotationAndExcessivePrecision() {
        assertThatThrownBy(() -> CnyPrecisionPolicy.toAtoms("1e-3")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> CnyPrecisionPolicy.toAtoms("0.000000001")).isInstanceOf(IllegalArgumentException.class);
    }
    @Test void aggregatesAllTokenCategoriesBeforeSingleCeilingRound() {
        var charge=CnyPrecisionPolicy.quoteCharge(1, 1, 0, 0,
                new BigInteger("1"), new BigInteger("1"), null, null);
        // 2 / 1,000,000 atom，最终按全局规则仅向上取整一次。
        assertThat(charge.preRoundNumerator()).isEqualTo(new BigInteger("2"));
        assertThat(charge.finalAtoms()).isEqualTo(BigInteger.ONE);
    }
    @Test void convertsValidatedStringToDatabaseScaleWithoutChangingValue() {
        assertThat(CnyPrecisionPolicy.toDecimal("12.34000001").toPlainString()).isEqualTo("12.34000001");
    }
}
