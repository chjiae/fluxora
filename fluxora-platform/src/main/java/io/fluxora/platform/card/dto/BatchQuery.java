package io.fluxora.platform.card.dto;

import java.math.BigDecimal;

/** 卡密批次列表查询条件 */
public record BatchQuery(
        String keyword,
        String status,
        BigDecimal denomination,
        Long tenantId,
        Integer page,
        Integer size
) {
    public int pageOrDefault() { return page == null || page < 1 ? 1 : page; }
    public int sizeOrDefault() {
        if (size == null || size < 1) return 20;
        return Math.min(size, 100);
    }
}