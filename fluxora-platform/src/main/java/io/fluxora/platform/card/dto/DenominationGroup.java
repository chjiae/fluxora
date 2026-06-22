package io.fluxora.platform.card.dto;

import java.math.BigDecimal;
import java.time.Instant;

/** 单组面额配置：用户一次可提交多组，service 为每组生成独立批次 */
public record DenominationGroup(
        BigDecimal denomination,
        Integer count,
        String name,
        Instant expireAt
) {}