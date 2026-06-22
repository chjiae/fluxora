package io.fluxora.platform.credit.dto;

import java.time.Instant;

/**
 * 流水查询条件。
 *   keyword       —— 用户名 / 显示名称模糊匹配
 *   direction     —— CREDIT / DEBIT
 *   userId        —— 按用户精确过滤（管理员视角）
 *   tenantId      —— 平台管理员跨租户过滤
 *   from / to     —— ISO-8601 区间（含端点）
 */
public record CreditTransactionQuery(
        String keyword,
        String direction,
        Long userId,
        Long tenantId,
        String from,
        String to,
        Integer page,
        Integer size
) {
    public int pageOrDefault() {
        return page == null || page < 1 ? 1 : page;
    }

    public int sizeOrDefault() {
        if (size == null || size < 1) return 20;
        return Math.min(size, 100);
    }

    public Instant parseFrom() {
        return parse(from);
    }

    public Instant parseTo() {
        return parse(to);
    }

    private static Instant parse(String iso) {
        if (iso == null || iso.isBlank()) return null;
        try {
            return Instant.parse(iso);
        } catch (Exception e) {
            return null;
        }
    }
}
