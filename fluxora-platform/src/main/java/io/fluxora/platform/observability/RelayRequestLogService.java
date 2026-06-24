package io.fluxora.platform.observability;

import io.fluxora.common.error.BusinessErrorCode;
import io.fluxora.platform.identity.entity.UserAccount;
import io.fluxora.platform.model.ModelException;
import io.fluxora.platform.upstream.security.UpstreamTenantGuard;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Platform 侧消费、权限收缩与查询门面；Gateway 永远不会调用该服务或任何 Mapper。 */
@Service
public class RelayRequestLogService {
    private final RelayRequestLogMapper mapper;
    private final UpstreamTenantGuard tenantGuard;
    private final ZoneId businessZone;

    public RelayRequestLogService(RelayRequestLogMapper mapper, UpstreamTenantGuard tenantGuard,
                                  @Value("${fluxora.observability.business-time-zone:Asia/Shanghai}") String businessTimeZone) {
        this.mapper = mapper;
        this.tenantGuard = tenantGuard;
        this.businessZone = ZoneId.of(businessTimeZone);
    }

    /** 同一数据库事务内先写 event_id 收据，再写请求记录；事务失败时调用方不得 XACK。 */
    @Transactional
    public boolean consume(Map<String, String> fields) {
        RelayEventPayload event = RelayEventPayload.from(fields);
        if (mapper.insertReceipt(event.eventId(), event.requestId(), event.eventType()) == 0) return false;
        if ("RELAY_REQUEST_STARTED".equals(event.eventType())) {
            mapper.insertStarted(event);
            return true;
        }
        Optional<String> amount = TheoreticalAmountCalculator.calculate(event.inputTokens(), event.outputTokens(),
                event.cacheWriteTokens(), event.cacheReadTokens(), event.inputPricePerMillion().toPlainString(),
                event.outputPricePerMillion().toPlainString(), decimalText(event.cacheWritePricePerMillion()),
                decimalText(event.cacheReadPricePerMillion()));
        String pricingStatus = amount.isPresent() ? "CALCULATED"
                : "PARTIAL".equals(event.pricingStatus()) ? "PARTIAL" : "UNAVAILABLE";
        mapper.upsertTerminal(event, amount.map(BigDecimal::new).orElse(null), pricingStatus);
        return true;
    }

    @Transactional(readOnly = true)
    public RelayLogPage list(UserAccount user, Authentication auth, RequestLogFilter filter) {
        RelayRequestLogQuery query = query(user, auth, filter);
        return new RelayLogPage(mapper.findPage(query), mapper.countPage(query), filter.page(), filter.size());
    }

    @Transactional(readOnly = true)
    public RelayRequestLogDetail detail(UserAccount user, Authentication auth, Long tenantId, String requestId) {
        Scope scope = scope(user, auth, tenantId);
        return mapper.findDetail(requestId, scope.tenantId(), scope.userId())
                .orElseThrow(() -> new ModelException(BusinessErrorCode.RESOURCE_NOT_FOUND, "请求记录不存在"));
    }

    @Transactional(readOnly = true)
    public RelayRequestLogStats stats(UserAccount user, Authentication auth, RequestLogFilter filter) {
        return mapper.stats(query(user, auth, filter));
    }

    @Transactional(readOnly = true)
    public RelayTrendResponse trends(UserAccount user, Authentication auth, RequestLogFilter filter, String range) {
        TimeRange resolved = TimeRange.resolve(range, businessZone);
        RequestLogFilter withRange = filter.withTimeRange(resolved.start(), resolved.end());
        RelayRequestLogQuery query = query(user, auth, withRange);
        List<RelayTrendBucket> buckets = mapper.trends(query, resolved.bucketUnit(), businessZone.getId());
        return new RelayTrendResponse(businessZone.getId(), resolved.range(), resolved.bucketUnit(),
                mapper.stats(query), buckets);
    }

    @Transactional(readOnly = true)
    public PricePreview preview(String inputTokens, String outputTokens, String cacheWriteTokens, String cacheReadTokens,
                                String inputPrice, String outputPrice, String cacheWritePrice, String cacheReadPrice) {
        Optional<String> amount = TheoreticalAmountCalculator.calculate(parse(inputTokens), parse(outputTokens),
                optional(parse(cacheWriteTokens)), optional(parse(cacheReadTokens)), inputPrice, outputPrice,
                blankToNull(cacheWritePrice), blankToNull(cacheReadPrice));
        return new PricePreview(amount.orElse(null), amount.isPresent() ? "CALCULATED" : "UNAVAILABLE");
    }

    private RelayRequestLogQuery query(UserAccount user, Authentication auth, RequestLogFilter filter) {
        Scope scope = scope(user, auth, filter.tenantId());
        Long requestedUser = scope.userId() == null ? filter.userId() : scope.userId();
        return new RelayRequestLogQuery(scope.tenantId(), requestedUser, filter.requestId(), filter.tenantModelCode(),
                filter.protocol(), filter.apiKeyId(), requestedUser, filter.requestStatus(), filter.startAt(), filter.endAt(),
                filter.size(), (filter.page() - 1) * filter.size());
    }

    private Scope scope(UserAccount user, Authentication auth, Long requestedTenantId) {
        if (tenantGuard.isPlatformAdmin(auth)) {
            if (requestedTenantId == null) throw new ModelException(BusinessErrorCode.VALIDATION_ERROR, "请选择目标租户");
            return new Scope(requestedTenantId, null);
        }
        if (user == null || user.getTenantId() == null) throw new ModelException(BusinessErrorCode.ACCESS_DENIED, "当前账号没有查看权限");
        if (requestedTenantId != null && !requestedTenantId.equals(user.getTenantId())) {
            throw new ModelException(BusinessErrorCode.CROSS_TENANT_ACCESS_DENIED, "不能查看其他租户的请求记录");
        }
        boolean tenantAdmin = auth != null && auth.getAuthorities().stream()
                .anyMatch(authority -> "ROLE_TENANT_ADMIN".equals(authority.getAuthority()));
        return new Scope(user.getTenantId(), tenantAdmin ? null : user.getId());
    }

    private static String decimalText(BigDecimal value) { return value == null ? null : value.toPlainString(); }
    private static Long parse(String value) { return value == null || value.isBlank() ? null : Long.parseLong(value); }
    private static Long optional(Long value) { return value; }
    private static String blankToNull(String value) { return value == null || value.isBlank() ? null : value; }
    private record Scope(long tenantId, Long userId) { }
    private record TimeRange(String range, Instant start, Instant end, String bucketUnit) {
        static TimeRange resolve(String range, ZoneId zone) {
            String normalized = range == null ? "TODAY" : range;
            ZonedDateTime now = ZonedDateTime.now(zone);
            return switch (normalized) {
                case "SEVEN_DAYS" -> new TimeRange(normalized, now.toLocalDate().minusDays(6).atStartOfDay(zone).toInstant(), now.toInstant(), "day");
                case "THIRTY_DAYS" -> new TimeRange(normalized, now.toLocalDate().minusDays(29).atStartOfDay(zone).toInstant(), now.toInstant(), "day");
                default -> new TimeRange("TODAY", now.toLocalDate().atStartOfDay(zone).toInstant(), now.toInstant(), "hour");
            };
        }
    }

    public record RequestLogFilter(Long tenantId, String requestId, String tenantModelCode, String protocol,
                                   Long apiKeyId, Long userId, String requestStatus, Instant startAt, Instant endAt,
                                   int page, int size) {
        public RequestLogFilter { page = page < 1 ? 1 : page; size = size < 1 ? 20 : Math.min(size, 100); }
        RequestLogFilter withTimeRange(Instant start, Instant end) { return new RequestLogFilter(tenantId, requestId, tenantModelCode, protocol, apiKeyId, userId, requestStatus, start, end, page, size); }
    }
    public record RelayLogPage(List<RelayRequestLogSummary> items, long total, int page, int size) { }
    public record RelayTrendResponse(String timeZone, String range, String granularity, RelayRequestLogStats stats,
                                     List<RelayTrendBucket> buckets) { }
    public record PricePreview(String theoreticalAmount, String pricingStatus) { }
}
