package io.fluxora.platform.identity.dto;

/**
 * 成员管理列表查询条件。
 *
 * keyword 模糊匹配 username/display_name/email；status 取值 ENABLED/DISABLED；
 * roleCode 取值如 TENANT_ADMIN/TENANT_MEMBER。所有字段均为可选过滤。
 */
public record MemberQuery(
        String keyword,
        String status,
        String roleCode,
        Integer page,
        Integer size
) {
    public int pageOrDefault() {
        return page == null || page < 1 ? 1 : page;
    }

    public int sizeOrDefault() {
        if (size == null || size < 1) return 20;
        // 上限保护，避免请求方传入过大 size 触发慢查询
        return Math.min(size, 100);
    }
}
