package io.fluxora.platform.billing.reservation;

import io.fluxora.platform.observability.RelayEventPayload;
import java.math.BigDecimal;
import java.time.Instant;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Redis Stream 终态的资金处理器。只有完整 usage 且实际金额不超过冻结额才自动结算；
 * 任何不确定性都只转 RECONCILIATION_PENDING，绝不自动释放或补扣。
 */
@Service
public class ReservationSettlementService {
    private final BillingReservationMapper mapper;

    public ReservationSettlementService(BillingReservationMapper mapper) {
        this.mapper = mapper;
    }

    @Transactional
    public ReservationFinalization finalizeTerminal(RelayEventPayload event, BigDecimal actualAmount, String pricingStatus) {
        BillingReservationRow row = mapper.findByRequestIdForUpdate(event.requestId()).orElse(null);
        if (row == null || !"RESERVED".equals(row.status())) return row == null ? ReservationFinalization.none() : ReservationFinalization.from(row);
        String dispatchState = safeDispatchState(event.upstreamDispatchState());
        if ("NOT_DISPATCHED".equals(dispatchState)) return release(row, "UPSTREAM_NOT_DISPATCHED", dispatchState);
        if (!"REPORTED".equals(event.usageStatus()) || !"CALCULATED".equals(pricingStatus) || actualAmount == null) {
            return pending(row, null, BigDecimal.ZERO, "USAGE_UNKNOWN", dispatchState);
        }
        if (actualAmount.compareTo(row.reservationAmount()) > 0) {
            return pending(row, actualAmount, actualAmount.subtract(row.reservationAmount()),
                    "ACTUAL_EXCEEDS_RESERVATION", dispatchState);
        }
        return settle(row, actualAmount, dispatchState);
    }

    /** Gateway 崩溃或终态永久延迟时只转待对账；超时绝不推断为“未派发”。 */
    @Transactional
    public int moveStaleReservationsToReconciliation(Instant olderThan) {
        return mapper.markStaleReservedPending(olderThan);
    }

    private ReservationFinalization settle(BillingReservationRow row, BigDecimal actualAmount, String dispatchState) {
        BigDecimal release = row.reservationAmount().subtract(actualAmount);
        WalletMutation mutation = mapper.settleWallet(row.tenantId(), row.userId(), row.reservationAmount(), actualAmount, release);
        if (mutation == null) return pending(row, actualAmount, BigDecimal.ZERO, "WALLET_FROZEN_MISMATCH", dispatchState);
        mapper.markSettled(row.requestId(), actualAmount, release, dispatchState);
        if (actualAmount.signum() > 0) {
            mapper.insertBillingTransaction(new BillingTransactionRow(row.tenantId(), row.userId(), "DEBIT", actualAmount,
                    mutation.balanceBefore(), mutation.balanceBefore(), mutation.frozenBalanceBefore(),
                    mutation.frozenBalanceBefore().subtract(actualAmount), "SETTLE", row.id(), "模型请求最终结算"));
        }
        if (release.signum() > 0) {
            mapper.insertBillingTransaction(new BillingTransactionRow(row.tenantId(), row.userId(), "CREDIT", release,
                    mutation.balanceBefore(), mutation.balanceAfter(), mutation.frozenBalanceBefore().subtract(actualAmount),
                    mutation.frozenBalanceAfter(), "RELEASE", row.id(), "模型请求结算差额释放"));
        }
        return new ReservationFinalization("SETTLED", row.reservationAmount(), actualAmount, release, BigDecimal.ZERO);
    }

    private ReservationFinalization release(BillingReservationRow row, String reasonCode, String dispatchState) {
        WalletMutation mutation = mapper.releaseWallet(row.tenantId(), row.userId(), row.reservationAmount());
        if (mutation == null) return pending(row, null, BigDecimal.ZERO, "WALLET_FROZEN_MISMATCH", dispatchState);
        mapper.markReleased(row.requestId(), row.reservationAmount(), reasonCode, dispatchState);
        if (row.reservationAmount().signum() > 0) {
            mapper.insertBillingTransaction(new BillingTransactionRow(row.tenantId(), row.userId(), "CREDIT", row.reservationAmount(),
                    mutation.balanceBefore(), mutation.balanceAfter(), mutation.frozenBalanceBefore(),
                    mutation.frozenBalanceAfter(), "RELEASE", row.id(), "上游未派发，完整释放预冻结"));
        }
        return new ReservationFinalization("RELEASED", row.reservationAmount(), BigDecimal.ZERO,
                row.reservationAmount(), BigDecimal.ZERO);
    }

    private ReservationFinalization pending(BillingReservationRow row, BigDecimal actualAmount,
                                            BigDecimal outstandingAmount, String reasonCode, String dispatchState) {
        mapper.markReconciliationPending(row.requestId(), actualAmount, outstandingAmount, reasonCode, dispatchState);
        return new ReservationFinalization("RECONCILIATION_PENDING", row.reservationAmount(), actualAmount,
                BigDecimal.ZERO, outstandingAmount);
    }

    private String safeDispatchState(String state) {
        String normalized = state == null ? "UNKNOWN" : state;
        return switch (normalized) {
            case "NOT_DISPATCHED", "DISPATCHED", "RESPONSE_STARTED", "UNKNOWN" -> normalized;
            default -> "UNKNOWN";
        };
    }
}
