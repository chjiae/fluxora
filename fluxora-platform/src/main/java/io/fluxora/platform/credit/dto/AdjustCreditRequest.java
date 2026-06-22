package io.fluxora.platform.credit.dto;

import java.math.BigDecimal;

/**
 * 调整额度请求体。
 *   direction —— CREDIT（增加）/ DEBIT（扣减）；service 强制只接受这两个值
 *   amount    —— 正数 BigDecimal；service 兜底拒绝 <= 0
 *   reason    —— 必填，2-256 字符
 */
public record AdjustCreditRequest(
        String direction,
        BigDecimal amount,
        String reason
) {}
