package io.fluxora.platform.card.dto;

import tools.jackson.databind.annotation.JsonDeserialize;
import io.fluxora.platform.billing.DecimalStringDeserializer;
import java.time.Instant;

/** 单组面额配置：用户一次可提交多组，service 为每组生成独立批次 */
public record DenominationGroup(
        /** 金额以十进制字符串接收，统一由服务层转换为八位原子精度。 */
        @JsonDeserialize(using = DecimalStringDeserializer.class) String denomination,
        Integer count,
        String name,
        Instant expireAt
) {}
