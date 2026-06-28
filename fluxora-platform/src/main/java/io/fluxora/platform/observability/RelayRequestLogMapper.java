package io.fluxora.platform.observability;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

/** 请求观测 Mapper：所有 SQL 位于对应 XML；事件收据、写入与查询均按租户索引访问。 */
@Mapper
interface RelayRequestLogMapper {
    int insertReceipt(@Param("eventId") String eventId, @Param("requestId") String requestId, @Param("eventType") String eventType);
    int insertStarted(RelayEventPayload event);
    int upsertTerminal(@Param("event") RelayEventPayload event, @Param("theoreticalAmount") BigDecimal theoreticalAmount, @Param("pricingStatus") String pricingStatus);
    void updateBillingStatus(@Param("requestId") String requestId, @Param("billingStatus") String billingStatus,
                             @Param("actualAmount") BigDecimal actualAmount,
                             @Param("outstandingAmount") BigDecimal outstandingAmount);
    List<RelayRequestLogSummary> findPage(RelayRequestLogQuery query);
    long countPage(RelayRequestLogQuery query);
    Optional<RelayRequestLogDetail> findDetail(@Param("requestId") String requestId, @Param("tenantId") long tenantId, @Param("userId") Long userId);
    RelayRequestLogStats stats(RelayRequestLogQuery query);
    List<RelayTrendBucket> trends(@Param("query") RelayRequestLogQuery query, @Param("bucketUnit") String bucketUnit, @Param("timeZone") String timeZone);
}
