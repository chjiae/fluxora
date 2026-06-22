package io.fluxora.platform.card.mapper;

import io.fluxora.platform.card.RechargeCardBatch;
import io.fluxora.platform.card.RechargeCard;
import io.fluxora.platform.card.dto.CardBatchSummary;
import io.fluxora.platform.card.dto.CardSummary;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

@Mapper
public interface CardMapper {

    // ---- 批次 ----
    void insertBatch(RechargeCardBatch batch);
    Optional<RechargeCardBatch> findBatchById(@Param("id") Long id);
    Optional<RechargeCardBatch> findBatchByIdIncludeTenant(@Param("id") Long id, @Param("tenantId") Long tenantId);
    List<CardBatchSummary> findBatchSummaries(
            @Param("tenantId") Long tenantId, @Param("keyword") String keyword,
            @Param("status") String status, @Param("denomination") BigDecimal denomination,
            @Param("offset") int offset, @Param("limit") int limit);
    long countBatches(@Param("tenantId") Long tenantId, @Param("keyword") String keyword,
                      @Param("status") String status, @Param("denomination") BigDecimal denomination);
    void setBatchStatus(@Param("id") Long id, @Param("status") String status);

    // ---- 卡密 ----
    void insertCard(RechargeCard card);
    /** 按 hash 查找（核销时用）；返回完整 entity 含 tenant_id / batch_id / status */
    Optional<RechargeCard> findByHash(@Param("cardHash") String cardHash);
    /** 查询单张（脱敏，含 join 后的用户/批次信息） */
    Optional<CardSummary> findCardSummary(@Param("id") Long id);
    /** 原子核销：UPDATE status='REDEEMED' WHERE 当前状态为 ENABLED 且未过期 */
    BigDecimal atomicRedeem(@Param("id") Long id, @Param("userId") Long userId, @Param("now") Instant now);
    List<CardSummary> findCardSummaries(
            @Param("batchId") Long batchId, @Param("tenantId") Long tenantId,
            @Param("prefixKeyword") String prefixKeyword, @Param("status") String status,
            @Param("offset") int offset, @Param("limit") int limit);
    long countCards(@Param("batchId") Long batchId, @Param("tenantId") Long tenantId,
                    @Param("prefixKeyword") String prefixKeyword, @Param("status") String status);
    void setCardStatus(@Param("id") Long id, @Param("status") String status,
                       @Param("disabledReason") String disabledReason);
}