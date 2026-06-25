package io.fluxora.platform.billing.reservation;

import io.fluxora.platform.billing.CnyPrecisionPolicy;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.HexFormat;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 预冻结事务门面。Gateway 只能经受控内部 HTTP 调用本服务，不能访问 Mapper 或直接修改余额。
 * 同一个 requestId 的参数指纹固定，重复调用只返回第一次结果，参数冲突绝不覆盖已有记录。
 */
@Service
public class BillingReservationService {
    private final BillingReservationMapper mapper;

    public BillingReservationService(BillingReservationMapper mapper) {
        this.mapper = mapper;
    }

    @Transactional
    public ReservationOutcome reserve(GatewayReservationRequest request) {
        validate(request);
        BigDecimal amount = CnyPrecisionPolicy.toDecimal(request.reservationAmount());
        String expected = ReservationAmountCalculator.calculate(request.inputTokenCeiling(), request.outputTokenCeiling(),
                request.cacheWriteTokenCeiling(), request.cacheReadTokenCeiling(), request.inputPricePerMillion(),
                request.outputPricePerMillion(), request.cacheWritePricePerMillion(), request.cacheReadPricePerMillion());
        if (amount.compareTo(CnyPrecisionPolicy.toDecimal(expected)) != 0) {
            return new ReservationOutcome("CONFLICT", "AMOUNT_MISMATCH", null);
        }
        String fingerprint = fingerprint(request);
        BillingReservationRow existing = mapper.findByRequestId(request.requestId()).orElse(null);
        if (existing != null) return existingOutcome(existing, fingerprint);

        WalletAccountRow wallet = mapper.findWallet(request.tenantId(), request.userId()).orElse(null);
        BigDecimal inputPrice = CnyPrecisionPolicy.toDecimal(request.inputPricePerMillion());
        BigDecimal outputPrice = CnyPrecisionPolicy.toDecimal(request.outputPricePerMillion());
        BigDecimal cacheWritePrice = optionalDecimal(request.cacheWritePricePerMillion());
        BigDecimal cacheReadPrice = optionalDecimal(request.cacheReadPricePerMillion());
        int inserted = mapper.insertReservation(request, fingerprint, wallet == null ? null : wallet.id(), amount,
                inputPrice, outputPrice, cacheWritePrice, cacheReadPrice);
        if (inserted == 0) {
            return existingOutcome(mapper.findByRequestId(request.requestId()).orElseThrow(), fingerprint);
        }
        if (wallet == null || !CnyPrecisionPolicy.CURRENCY_CODE.equals(wallet.currencyCode())) {
            mapper.markReserveRejected(request.requestId(), "ACCOUNT_UNAVAILABLE");
            return new ReservationOutcome("RESERVE_REJECTED", "ACCOUNT_UNAVAILABLE", null);
        }
        WalletMutation mutation = mapper.reserveWallet(request.tenantId(), request.userId(), amount);
        if (mutation == null) {
            mapper.markReserveRejected(request.requestId(), "INSUFFICIENT_BALANCE");
            return new ReservationOutcome("RESERVE_REJECTED", "INSUFFICIENT_BALANCE", null);
        }
        if (amount.signum() > 0) {
            mapper.insertBillingTransaction(new BillingTransactionRow(request.tenantId(), request.userId(), "DEBIT", amount,
                    mutation.balanceBefore(), mutation.balanceAfter(), mutation.frozenBalanceBefore(),
                    mutation.frozenBalanceAfter(), "RESERVE", mapper.findByRequestId(request.requestId()).orElseThrow().id(),
                    "模型请求预冻结"));
        }
        return new ReservationOutcome("RESERVED", null, amount.toPlainString());
    }

    @Transactional(readOnly = true)
    public ReservationOutcome status(String requestId) {
        if (requestId == null || requestId.isBlank() || requestId.length() > 64) {
            return new ReservationOutcome("NOT_FOUND", "INVALID_REQUEST", null);
        }
        BillingReservationRow row = mapper.findByRequestId(requestId).orElse(null);
        return row == null ? new ReservationOutcome("NOT_FOUND", "NOT_FOUND", null)
                : new ReservationOutcome(row.status(), row.reasonCode(), row.reservationAmount().toPlainString());
    }

    private ReservationOutcome existingOutcome(BillingReservationRow existing, String fingerprint) {
        if (!existing.requestFingerprint().equals(fingerprint)) {
            return new ReservationOutcome("CONFLICT", "PARAMETER_CONFLICT", null);
        }
        return new ReservationOutcome(existing.status(), existing.reasonCode(), existing.reservationAmount().toPlainString());
    }

    private void validate(GatewayReservationRequest request) {
        if (request == null || blank(request.requestId()) || request.requestId().length() > 64
                || !positive(request.tenantId()) || !positive(request.userId()) || !positive(request.apiKeyId())
                || !positive(request.tenantModelId()) || blank(request.tenantModelCode()) || request.tenantModelCode().length() > 128
                || !("OPENAI".equals(request.inboundProtocol()) || "ANTHROPIC".equals(request.inboundProtocol()))
                || blank(request.endpoint()) || request.endpoint().length() > 128 || blank(request.currencyCode())
                || !CnyPrecisionPolicy.CURRENCY_CODE.equals(request.currencyCode()) || request.priceVersion() == null
                || request.priceVersion() < 1 || blank(request.reservationAmount())) {
            throw new IllegalArgumentException("预冻结请求不合法");
        }
        try {
            Instant.parse(request.requestStartedAt());
            ReservationAmountCalculator.calculate(request.inputTokenCeiling(), request.outputTokenCeiling(),
                    request.cacheWriteTokenCeiling(), request.cacheReadTokenCeiling(), request.inputPricePerMillion(),
                    request.outputPricePerMillion(), request.cacheWritePricePerMillion(), request.cacheReadPricePerMillion());
            CnyPrecisionPolicy.toDecimal(request.reservationAmount());
        } catch (RuntimeException exception) {
            throw new IllegalArgumentException("预冻结快照不合法");
        }
    }

    private String fingerprint(GatewayReservationRequest request) {
        String canonical = String.join("\n", request.requestId(), request.tenantId().toString(), request.userId().toString(),
                request.apiKeyId().toString(), request.tenantModelId().toString(), request.tenantModelCode(),
                request.inboundProtocol(), request.endpoint(), request.currencyCode(), request.priceVersion().toString(),
                request.inputPricePerMillion(), request.outputPricePerMillion(), nullToEmpty(request.cacheWritePricePerMillion()),
                nullToEmpty(request.cacheReadPricePerMillion()), request.inputTokenCeiling().toString(),
                request.outputTokenCeiling().toString(), request.cacheWriteTokenCeiling().toString(),
                request.cacheReadTokenCeiling().toString(), request.reservationAmount());
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(canonical.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("摘要算法不可用", exception);
        }
    }

    private static boolean positive(Long value) { return value != null && value > 0L; }
    private static boolean blank(String value) { return value == null || value.isBlank(); }
    private static String nullToEmpty(String value) { return value == null ? "" : value; }
    private static BigDecimal optionalDecimal(String value) { return value == null ? null : CnyPrecisionPolicy.toDecimal(value); }
}
