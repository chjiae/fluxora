package io.fluxora.platform.apikey.dto;

/**
 * API Key 列表查询条件。
 *
 * 字段含义：
 *   keyword       —— 同时匹配 name、key_prefix、username、display_name
 *   status        —— ENABLED / DISABLED / EXPIRED；DELETED 默认被过滤
 *   userId        —— 平台/租户管理员视角下按用户筛选
 *   tenantId      —— 平台管理员跨租户视角下按租户筛选
 *   page, size    —— 分页；上限由服务层兜底
 */
public record ApiKeyQuery(
        String keyword,
        String status,
        Long userId,
        Long tenantId,
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
}
