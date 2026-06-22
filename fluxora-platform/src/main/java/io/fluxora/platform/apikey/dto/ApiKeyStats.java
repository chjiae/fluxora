package io.fluxora.platform.apikey.dto;

/**
 * API Key 聚合统计（指标条用）。
 *
 * 字段：
 *   total         —— 范围内未软删 Key 总数
 *   enabled       —— 启用且未过期
 *   disabled      —— 停用且未过期
 *   expired       —— 已过期（无论 enabled）
 *   expiringSoon  —— 启用中、30 天内即将到期
 */
public record ApiKeyStats(
        long total,
        long enabled,
        long disabled,
        long expired,
        long expiringSoon
) {}
