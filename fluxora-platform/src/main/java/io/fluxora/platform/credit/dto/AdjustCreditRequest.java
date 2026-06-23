package io.fluxora.platform.credit.dto;

import tools.jackson.databind.annotation.JsonDeserialize;
import io.fluxora.platform.billing.DecimalStringDeserializer;

/**
 * 调整额度请求体。
 *   direction —— CREDIT（增加）/ DEBIT（扣减）；service 强制只接受这两个值
 *   amount    —— 十进制字符串；控制器不接受 JSON Number，避免前端精度丢失或科学计数法绕过校验
 *   reason    —— 必填，2-256 字符
 */
public record AdjustCreditRequest(
        String direction,
        @JsonDeserialize(using = DecimalStringDeserializer.class) String amount,
        String reason
) {}
