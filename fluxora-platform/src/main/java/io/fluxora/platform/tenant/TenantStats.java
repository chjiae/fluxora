package io.fluxora.platform.tenant;

/**
 * 租户聚合统计 DTO。
 *
 * 用于「概览」与「租户管理」页面顶部的指标条；MyBatis 通过
 * map-underscore-to-camel-case 自动从聚合 SQL 的列别名映射。
 *
 * 字段含义：
 *   - total：未删除租户总数（含已停用、已过期；不含 deleted_at IS NOT NULL）
 *   - enabled：当前有效（启用且未过期）租户数，与前端「启用」状态点对应
 *   - disabled：已停用租户数（enabled = FALSE 且未过期）
 *   - expired：已过期租户数（expire_at IS NOT NULL AND expire_at <= NOW()）
 *   - expiringSoon：即将到期租户数（启用中且 expire_at 在「现在 + N 天」之间）
 *   - selfOperated：自营租户数（type = 'SELF_OPERATED'）
 */
public record TenantStats(
        long total,
        long enabled,
        long disabled,
        long expired,
        long expiringSoon,
        long selfOperated
) {}
