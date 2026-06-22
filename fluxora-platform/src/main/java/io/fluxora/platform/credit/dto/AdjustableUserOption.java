package io.fluxora.platform.credit.dto;

import java.math.BigDecimal;

/**
 * 可调整额度的用户选项（管理员调整额度时的下拉数据源）。
 * 仅包含必要展示字段；不返回邮箱、密码哈希等敏感字段。
 */
public record AdjustableUserOption(
        Long userId,
        String username,
        String userDisplayName,
        Long tenantId,
        String tenantCode,
        String tenantName,
        BigDecimal balance
) {}
