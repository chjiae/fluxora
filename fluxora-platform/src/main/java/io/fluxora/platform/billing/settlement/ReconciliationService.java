package io.fluxora.platform.billing.settlement;

import io.fluxora.common.error.BusinessErrorCode;
import io.fluxora.platform.billing.CnyPrecisionPolicy;
import io.fluxora.platform.credit.CreditException;
import io.fluxora.platform.identity.entity.UserAccount;
import io.fluxora.platform.runtime.RuntimeOutboxService;
import io.fluxora.platform.upstream.security.UpstreamTenantGuard;
import java.math.BigDecimal;
import java.util.List;
import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** 平台管理员待对账门面；只允许直接扣费或确认不扣费。 */
@Service
public class ReconciliationService {
    private final BillingSettlementMapper mapper;
    private final UpstreamTenantGuard tenantGuard;
    private final RuntimeOutboxService runtimeOutboxService;

    public ReconciliationService(BillingSettlementMapper mapper, UpstreamTenantGuard tenantGuard,
                                 RuntimeOutboxService runtimeOutboxService) {
        this.mapper = mapper;
        this.tenantGuard = tenantGuard;
        this.runtimeOutboxService = runtimeOutboxService;
    }

    @Transactional(readOnly = true)
    public ReconciliationPage pending(UserAccount user, Authentication authentication, Long tenantId, int page, int size) {
        assertPlatformAdmin(authentication);
        int safePage = Math.max(1, page);
        int safeSize = Math.max(1, Math.min(size, 100));
        List<BillingSettlementView> items = mapper.findPendingPage(tenantId, (safePage - 1) * safeSize, safeSize)
                .stream().map(BillingSettlementView::from).toList();
        return new ReconciliationPage(items, mapper.countPending(tenantId), safePage, safeSize);
    }

    @Transactional
    public BillingSettlementView confirmSettle(UserAccount user, Authentication authentication, String requestId,
                                               ReconciliationActionRequest request) {
        assertPlatformAdmin(authentication);
        BillingSettlementRow row = locked(requestId);
        if (!"RECONCILIATION_PENDING".equals(row.status())) {
            return BillingSettlementView.from(row);
        }
        String reason = validReason(request);
        BigDecimal actual;
        try {
            actual = CnyPrecisionPolicy.toDecimal(request == null ? null : request.finalAmount());
        } catch (RuntimeException exception) {
            throw new CreditException(BusinessErrorCode.CREDIT_AMOUNT_INVALID);
        }
        BillingWalletMutation mutation = mapper.debitWallet(row.tenantId(), row.userId(), actual);
        mapper.markManualSettled(row.requestId(), actual, user.getId(), reason);
        mapper.insertBillingTransaction(new BillingSettlementTransaction(row.tenantId(), row.userId(), "DEBIT",
                actual, mutation.balanceBefore(), mutation.balanceAfter(), "MODEL_USAGE", row.id(),
                "平台人工确认模型请求结算"));
        publishEligibilityIfCrossed(row.tenantId(), row.userId(), mutation.balanceBefore(), mutation.balanceAfter());
        return BillingSettlementView.from(mapper.findByRequestId(row.requestId()).orElseThrow());
    }

    @Transactional
    public BillingSettlementView confirmNoCharge(UserAccount user, Authentication authentication, String requestId,
                                                 ReconciliationActionRequest request) {
        assertPlatformAdmin(authentication);
        BillingSettlementRow row = locked(requestId);
        if (!"RECONCILIATION_PENDING".equals(row.status())) {
            return BillingSettlementView.from(row);
        }
        String reason = validReason(request);
        mapper.markManualNoCharge(row.requestId(), user.getId(), reason);
        return BillingSettlementView.from(mapper.findByRequestId(row.requestId()).orElseThrow());
    }

    private BillingSettlementRow locked(String requestId) {
        return mapper.findByRequestIdForUpdate(requestId)
                .orElseThrow(() -> new CreditException(BusinessErrorCode.RESOURCE_NOT_FOUND));
    }

    private void assertPlatformAdmin(Authentication authentication) {
        if (!tenantGuard.isPlatformAdmin(authentication)) {
            throw new CreditException(BusinessErrorCode.ACCESS_DENIED);
        }
    }

    private String validReason(ReconciliationActionRequest request) {
        String reason = request == null ? null : request.reason();
        if (reason == null || reason.isBlank() || reason.length() > 256) {
            throw new CreditException(BusinessErrorCode.CREDIT_REASON_REQUIRED);
        }
        return reason.trim();
    }

    private void publishEligibilityIfCrossed(Long tenantId, Long userId, BigDecimal before, BigDecimal after) {
        boolean wasAllowed = before.compareTo(BigDecimal.ZERO) > 0;
        boolean isAllowed = after.compareTo(BigDecimal.ZERO) > 0;
        if (wasAllowed != isAllowed) {
            runtimeOutboxService.record(tenantId, "USER_ACCOUNT", userId, "BILLING_ELIGIBILITY_CHANGED", null);
        }
    }
}
