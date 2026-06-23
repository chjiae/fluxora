package io.fluxora.platform.card.dto;

/** 卡密列表查询条件 */
public record CardQuery(
        Long batchId,
        String prefixKeyword,
        String denomination,
        String status,
        Long redeemedUserId,
        Integer page,
        Integer size
) {
    public int pageOrDefault() { return page == null || page < 1 ? 1 : page; }
    public int sizeOrDefault() {
        if (size == null || size < 1) return 20;
        return Math.min(size, 100);
    }
}
