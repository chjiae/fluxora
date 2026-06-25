package io.fluxora.platform.billing.reservation;

import io.fluxora.common.error.BusinessErrorCode;
import io.fluxora.platform.billing.CnyPrecisionPolicy;
import io.fluxora.platform.credit.CreditException;
import io.fluxora.platform.identity.entity.UserAccount;
import io.fluxora.platform.upstream.security.UpstreamTenantGuard;
import java.math.BigDecimal;
import java.util.List;
import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** 仅平台管理员可用的受控人工确认；任何超额金额继续保留待对账，禁止追缴或负余额。 */
@Service
public class ReconciliationService {
    private final BillingReservationMapper mapper;
    private final UpstreamTenantGuard tenantGuard;

    public ReconciliationService(BillingReservationMapper mapper, UpstreamTenantGuard tenantGuard) {
        this.mapper = mapper;
        this.tenantGuard = tenantGuard;
    }

    @Transactional(readOnly = true)
    public ReconciliationPage pending(UserAccount user, Authentication authentication, Long tenantId, int page, int size) {
        assertPlatformAdmin(authentication);
        int safePage = Math.max(1, page);
        int safeSize = Math.max(1, Math.min(size, 100));
        List<BillingReservationView> items = mapper.findPendingPage(tenantId, (safePage - 1) * safeSize, safeSize)
                .stream().map(BillingReservationView::from).toList();
        return new ReconciliationPage(items, mapper.countPending(tenantId), safePage, safeSize);
    }

    @Transactional
    public BillingReservationView confirmRelease(UserAccount user, Authentication authentication, String requestId,
                                                 ReconciliationActionRequest request) {
        assertPlatformAdmin(authentication);
        BillingReservationRow row = locked(requestId);
        if (!"RECONCILIATION_PENDING".equals(row.status())) return BillingReservationView.from(row);
        String reason = validReason(request);
        WalletMutation mutation = mapper.releaseWallet(row.tenantId(), row.userId(), row.reservationAmount());
        if (mutation == null) throw new CreditException(BusinessErrorCode.INTERNAL_ERROR);
        mapper.markManualReconciledReleased(row.requestId(), row.reservationAmount(), user.getId(), reason);
        if (row.reservationAmount().signum() > 0) {
            mapper.insertBillingTransaction(new BillingTransactionRow(row.tenantId(), row.userId(), "CREDIT", row.reservationAmount(),
                    mutation.balanceBefore(), mutation.balanceAfter(), mutation.frozenBalanceBefore(), mutation.frozenBalanceAfter(),
                    "RELEASE", row.id(), "平台人工确认完整释放"));
        }
        return BillingReservationView.from(mapper.findByRequestId(row.requestId()).orElseThrow());
    }

    @Transactional
    public BillingReservationView confirmSettle(UserAccount user, Authentication authentication, String requestId,
                                                ReconciliationActionRequest request) {
        assertPlatformAdmin(authentication);
        BillingReservationRow row = locked(requestId);
        if (!"RECONCILIATION_PENDING".equals(row.status())) return BillingReservationView.from(row);
        String reason = validReason(request);
        BigDecimal actual;
        try { actual = CnyPrecisionPolicy.toDecimal(request == null ? null : request.finalAmount()); }
        catch (RuntimeException exception) { throw new CreditException(BusinessErrorCode.CREDIT_AMOUNT_INVALID); }
        if (actual.compareTo(row.reservationAmount()) > 0) {
            // 明确保留原待对账状态，不能以管理员操作绕过“不追缴超额”的产品边界。
            throw new CreditException(BusinessErrorCode.CREDIT_AMOUNT_INVALID);
        }
        BigDecimal release = row.reservationAmount().subtract(actual);
        WalletMutation mutation = mapper.settleWallet(row.tenantId(), row.userId(), row.reservationAmount(), actual, release);
        if (mutation == null) throw new CreditException(BusinessErrorCode.INTERNAL_ERROR);
        mapper.markManualReconciledSettled(row.requestId(), actual, release, user.getId(), reason);
        if (actual.signum() > 0) mapper.insertBillingTransaction(new BillingTransactionRow(row.tenantId(), row.userId(), "DEBIT", actual,
                mutation.balanceBefore(), mutation.balanceBefore(), mutation.frozenBalanceBefore(), mutation.frozenBalanceBefore().subtract(actual),
                "SETTLE", row.id(), "平台人工确认最终结算"));
        if (release.signum() > 0) mapper.insertBillingTransaction(new BillingTransactionRow(row.tenantId(), row.userId(), "CREDIT", release,
                mutation.balanceBefore(), mutation.balanceAfter(), mutation.frozenBalanceBefore().subtract(actual), mutation.frozenBalanceAfter(),
                "RELEASE", row.id(), "平台人工确认结算差额释放"));
        return BillingReservationView.from(mapper.findByRequestId(row.requestId()).orElseThrow());
    }

    private BillingReservationRow locked(String requestId) {
        return mapper.findByRequestIdForUpdate(requestId)
                .orElseThrow(() -> new CreditException(BusinessErrorCode.RESOURCE_NOT_FOUND));
    }
    private void assertPlatformAdmin(Authentication authentication) {
        if (!tenantGuard.isPlatformAdmin(authentication)) throw new CreditException(BusinessErrorCode.ACCESS_DENIED);
    }
    private String validReason(ReconciliationActionRequest request) {
        String reason = request == null ? null : request.reason();
        if (reason == null || reason.isBlank() || reason.length() > 256) throw new CreditException(BusinessErrorCode.CREDIT_REASON_REQUIRED);
        return reason.trim();
    }
}
