package io.fluxora.platform.billing;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.math.RoundingMode;

/**
 * 人民币账务、价格与未来计费唯一精度策略。
 * 1 CNY = 100,000,000 原子单位；余额、流水、卡密与单价均固定至 8 位小数，
 * 单价表示每百万 Token 的原子单位。计费先汇总分子，最后只向上取整一次。
 */
public final class CnyPrecisionPolicy {
    public static final String CURRENCY_CODE = "CNY";
    public static final int SCALE = 8;
    public static final BigInteger ATOMS_PER_CNY = BigInteger.TEN.pow(SCALE);
    public static final BigInteger TOKENS_PER_PRICE_UNIT = BigInteger.valueOf(1_000_000L);
    public static final BigInteger MAX_PRICE_ATOMS = new BigInteger("1000000000000000");
    public static final BigInteger MAX_TOKENS_PER_REQUEST = new BigInteger("1000000000000");
    private CnyPrecisionPolicy() {}
    public static BigInteger toAtoms(String text) {
        if (text == null || !text.matches("(?:0|[1-9]\\d*)(?:\\.\\d{1,8})?")) throw new IllegalArgumentException("金额格式不正确");
        BigDecimal value = new BigDecimal(text);
        return value.movePointRight(SCALE).setScale(0, RoundingMode.UNNECESSARY).toBigIntegerExact();
    }
    public static String formatAtoms(BigInteger atoms) {
        if (atoms == null) return null;
        BigDecimal v = new BigDecimal(atoms, SCALE).stripTrailingZeros();
        return v.scale() < 0 ? v.setScale(0).toPlainString() : v.toPlainString();
    }
    /**
     * 将外部十进制字符串转换为数据库使用的八位小数。调用方不得直接 new BigDecimal，
     * 从而统一拒绝科学计数法并确保余额、卡密和流水遵循同一币种精度。
     */
    public static BigDecimal toDecimal(String text) {
        return new BigDecimal(formatAtoms(toAtoms(text))).setScale(SCALE, RoundingMode.UNNECESSARY);
    }
    public static ChargeQuote quoteCharge(long input, long output, long cacheWrite, long cacheRead, BigInteger inputPrice, BigInteger outputPrice, BigInteger cacheWritePrice, BigInteger cacheReadPrice) {
        BigInteger numerator = line(input,inputPrice).add(line(output,outputPrice)).add(line(cacheWrite,cacheWritePrice)).add(line(cacheRead,cacheReadPrice));
        BigInteger result = numerator.add(TOKENS_PER_PRICE_UNIT.subtract(BigInteger.ONE)).divide(TOKENS_PER_PRICE_UNIT);
        return new ChargeQuote(numerator,result);
    }
    private static BigInteger line(long tokens, BigInteger price) {
        if(tokens<0||BigInteger.valueOf(tokens).compareTo(MAX_TOKENS_PER_REQUEST)>0||price==null) return BigInteger.ZERO;
        if(price.signum()<0||price.compareTo(MAX_PRICE_ATOMS)>0) throw new IllegalArgumentException("价格超出范围");
        return BigInteger.valueOf(tokens).multiply(price);
    }
    public record ChargeQuote(BigInteger preRoundNumerator, BigInteger finalAtoms) {}
}
