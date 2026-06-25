package io.fluxora.platform.billing.reservation;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

/** 预冻结、钱包原子迁移和待对账查询的唯一 MyBatis 契约；所有 SQL 位于 XML。 */
@Mapper
public interface BillingReservationMapper {
    Optional<BillingReservationRow> findByRequestId(@Param("requestId") String requestId);
    Optional<BillingReservationRow> findByRequestIdForUpdate(@Param("requestId") String requestId);
    Optional<WalletAccountRow> findWallet(@Param("tenantId") Long tenantId, @Param("userId") Long userId);
    int insertReservation(@Param("request") GatewayReservationRequest request, @Param("fingerprint") String fingerprint,
                          @Param("walletId") Long walletId, @Param("amount") BigDecimal amount,
                          @Param("inputPrice") BigDecimal inputPrice, @Param("outputPrice") BigDecimal outputPrice,
                          @Param("cacheWritePrice") BigDecimal cacheWritePrice, @Param("cacheReadPrice") BigDecimal cacheReadPrice);
    WalletMutation reserveWallet(@Param("tenantId") Long tenantId, @Param("userId") Long userId,
                                 @Param("amount") BigDecimal amount);
    WalletMutation settleWallet(@Param("tenantId") Long tenantId, @Param("userId") Long userId,
                                @Param("reservedAmount") BigDecimal reservedAmount,
                                @Param("actualAmount") BigDecimal actualAmount,
                                @Param("releasedAmount") BigDecimal releasedAmount);
    WalletMutation releaseWallet(@Param("tenantId") Long tenantId, @Param("userId") Long userId,
                                 @Param("amount") BigDecimal amount);
    void markReserveRejected(@Param("requestId") String requestId, @Param("reasonCode") String reasonCode);
    void markSettled(@Param("requestId") String requestId, @Param("actualAmount") BigDecimal actualAmount,
                     @Param("releasedAmount") BigDecimal releasedAmount, @Param("dispatchState") String dispatchState);
    void markReleased(@Param("requestId") String requestId, @Param("releasedAmount") BigDecimal releasedAmount,
                      @Param("reasonCode") String reasonCode, @Param("dispatchState") String dispatchState);
    void markReconciliationPending(@Param("requestId") String requestId, @Param("actualAmount") BigDecimal actualAmount,
                                   @Param("outstandingAmount") BigDecimal outstandingAmount,
                                   @Param("reasonCode") String reasonCode, @Param("dispatchState") String dispatchState);
    int markStaleReservedPending(@Param("olderThan") Instant olderThan);
    void insertBillingTransaction(BillingTransactionRow transaction);
    List<BillingReservationRow> findPendingPage(@Param("tenantId") Long tenantId, @Param("offset") int offset,
                                                @Param("limit") int limit);
    long countPending(@Param("tenantId") Long tenantId);
    void markManualReconciledSettled(@Param("requestId") String requestId, @Param("actualAmount") BigDecimal actualAmount,
                                     @Param("releasedAmount") BigDecimal releasedAmount, @Param("operatorId") Long operatorId,
                                     @Param("note") String note);
    void markManualReconciledReleased(@Param("requestId") String requestId, @Param("releasedAmount") BigDecimal releasedAmount,
                                      @Param("operatorId") Long operatorId, @Param("note") String note);
}
