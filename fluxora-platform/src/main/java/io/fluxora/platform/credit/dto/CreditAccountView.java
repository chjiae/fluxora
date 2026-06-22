package io.fluxora.platform.credit.dto;

import java.math.BigDecimal;
import java.time.Instant;

/**
 * 额度账户对外视图。
 * 包含 join 后的归属信息，便于平台/租户管理员查询任意用户的账户时直接展示。
 */
public record CreditAccountView(
        Long userId,
        String username,
        String userDisplayName,
        Long tenantId,
        String tenantCode,
        String tenantName,
        BigDecimal balance,
        Instant createdAt,
        Instant updatedAt
) {}
