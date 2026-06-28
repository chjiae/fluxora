package io.fluxora.platform.billing.settlement;

import io.fluxora.platform.observability.RelayEventPayload;
import io.fluxora.platform.runtime.RuntimeOutboxService;
import java.math.BigDecimal;
import java.time.Instant;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Redis Stream 终态的直接结算处理器。
 *
 * <p>可信 usage 与价格齐备时直接扣减真实余额；同一 requestId 在数据库唯一约束和事务锁下
 * 只会扣费一次。余额扣到 0 或负数时只影响后续快照准入，
 * 不会中断已经完成的当前请求。</p>
 */
@Service
public class BillingSettlementService {
    private final BillingSettlementMapper mapper;
    private final RuntimeOutboxService runtimeOutboxService;

    public BillingSettlementService(BillingSettlementMapper mapper, RuntimeOutboxService runtimeOutboxService) {
        this.mapper = mapper;
        this.runtimeOutboxService = runtimeOutboxService;
    }

    /**
     * 处理一次请求终态。NOT_DISPATCHED 不扣费；usage 或价格不可信时进入待对账；成功请求直接结算。
     */
    @Transactional
    public BillingSettlementFinalization finalizeTerminal(RelayEventPayload event, BigDecimal actualAmount,
                                                          String pricingStatus) {
        BillingSettlementRow existing = mapper.findByRequestIdForUpdate(event.requestId()).orElse(null);
        if (existing != null) {
            return BillingSettlementFinalization.from(existing);
        }
        String dispatchState = safeDispatchState(event.upstreamDispatchState());
        if ("NOT_DISPATCHED".equals(dispatchState)) {
            return noCharge(event, "UPSTREAM_NOT_DISPATCHED", dispatchState);
        }
        if (!"REPORTED".equals(event.usageStatus()) || !"CALCULATED".equals(pricingStatus) || actualAmount == null) {
            return pending(event, null, "USAGE_UNKNOWN", dispatchState);
        }
        return settle(event, actualAmount, dispatchState);
    }

    /** 终态永久缺失时只进入待对账，不推断为未派发，也不存在释放动作。 */
    @Transactional
    public int moveStaleRequestsToReconciliation(Instant olderThan) {
        return mapper.markStaleStartedPending(olderThan);
    }

    private BillingSettlementFinalization settle(RelayEventPayload event, BigDecimal actualAmount, String dispatchState) {
        Long settlementId = mapper.insertSettlement(event, actualAmount, BigDecimal.ZERO, "SETTLED", null, dispatchState);
        if (settlementId == null) {
            return mapper.findByRequestId(event.requestId())
                    .map(BillingSettlementFinalization::from)
                    .orElse(BillingSettlementFinalization.none());
        }
        BillingWalletMutation mutation = mapper.debitWallet(event.tenantId(), event.userId(), actualAmount);
        mapper.insertBillingTransaction(new BillingSettlementTransaction(event.tenantId(), event.userId(), "DEBIT",
                actualAmount, mutation.balanceBefore(), mutation.balanceAfter(), "MODEL_USAGE", settlementId,
                "模型请求最终直接结算"));
        publishEligibilityIfCrossed(event.tenantId(), event.userId(), mutation.balanceBefore(), mutation.balanceAfter());
        return new BillingSettlementFinalization("SETTLED", actualAmount, BigDecimal.ZERO);
    }

    private BillingSettlementFinalization pending(RelayEventPayload event, BigDecimal actualAmount,
                                                  String reasonCode, String dispatchState) {
        mapper.insertSettlement(event, actualAmount, BigDecimal.ZERO, "RECONCILIATION_PENDING", reasonCode, dispatchState);
        return new BillingSettlementFinalization("RECONCILIATION_PENDING", actualAmount, BigDecimal.ZERO);
    }

    private BillingSettlementFinalization noCharge(RelayEventPayload event, String reasonCode, String dispatchState) {
        mapper.insertSettlement(event, BigDecimal.ZERO, BigDecimal.ZERO, "NO_CHARGE", reasonCode, dispatchState);
        return new BillingSettlementFinalization("NO_CHARGE", BigDecimal.ZERO, BigDecimal.ZERO);
    }

    private void publishEligibilityIfCrossed(long tenantId, long userId, BigDecimal before, BigDecimal after) {
        boolean wasAllowed = before.compareTo(BigDecimal.ZERO) > 0;
        boolean isAllowed = after.compareTo(BigDecimal.ZERO) > 0;
        if (wasAllowed != isAllowed) {
            runtimeOutboxService.record(tenantId, "USER_ACCOUNT", userId, "BILLING_ELIGIBILITY_CHANGED", null);
        }
    }

    private String safeDispatchState(String state) {
        String normalized = state == null ? "UNKNOWN" : state;
        return switch (normalized) {
            case "NOT_DISPATCHED", "DISPATCHED", "RESPONSE_STARTED", "UNKNOWN" -> normalized;
            default -> "UNKNOWN";
        };
    }
}
