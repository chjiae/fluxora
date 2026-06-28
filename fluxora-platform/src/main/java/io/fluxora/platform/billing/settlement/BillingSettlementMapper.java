package io.fluxora.platform.billing.settlement;

import io.fluxora.platform.observability.RelayEventPayload;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

/** 直接结算与待对账的唯一 MyBatis 契约；所有 SQL 必须位于 XML。 */
@Mapper
public interface BillingSettlementMapper {
    Optional<BillingSettlementRow> findByRequestId(@Param("requestId") String requestId);

    Optional<BillingSettlementRow> findByRequestIdForUpdate(@Param("requestId") String requestId);

    Long insertSettlement(@Param("event") RelayEventPayload event,
                          @Param("actualAmount") BigDecimal actualAmount,
                          @Param("outstandingAmount") BigDecimal outstandingAmount,
                          @Param("status") String status,
                          @Param("reasonCode") String reasonCode,
                          @Param("dispatchState") String dispatchState);

    BillingWalletMutation debitWallet(@Param("tenantId") Long tenantId,
                                       @Param("userId") Long userId,
                                       @Param("amount") BigDecimal amount);

    void insertBillingTransaction(BillingSettlementTransaction transaction);

    int markStaleStartedPending(@Param("olderThan") Instant olderThan);

    List<BillingSettlementRow> findPendingPage(@Param("tenantId") Long tenantId,
                                               @Param("offset") int offset,
                                               @Param("limit") int limit);

    long countPending(@Param("tenantId") Long tenantId);

    void markManualSettled(@Param("requestId") String requestId,
                           @Param("actualAmount") BigDecimal actualAmount,
                           @Param("operatorId") Long operatorId,
                           @Param("note") String note);

    void markManualNoCharge(@Param("requestId") String requestId,
                            @Param("operatorId") Long operatorId,
                            @Param("note") String note);
}
