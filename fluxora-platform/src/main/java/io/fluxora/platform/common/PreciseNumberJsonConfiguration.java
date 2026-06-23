package io.fluxora.platform.common;

import tools.jackson.core.JsonGenerator;
import tools.jackson.databind.ValueSerializer;
import tools.jackson.databind.SerializationContext;
import tools.jackson.databind.module.SimpleModule;
import java.math.BigDecimal;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * 金额接口边界统一为字符串：浏览器不得把金额/余额/价格作为 IEEE-754 Number 接收。
 * 领域内部仍使用 BigDecimal，禁止由浮点数构造；非账务整数 ID 不受该配置影响。
 */
@Configuration
public class PreciseNumberJsonConfiguration {
    @Bean
    public SimpleModule preciseBigDecimalModule() {
        SimpleModule module = new SimpleModule("precise-bigdecimal-as-string");
        module.addSerializer(BigDecimal.class, new ValueSerializer<>() {
            @Override public void serialize(BigDecimal value, JsonGenerator gen, SerializationContext serializers) {
                gen.writeString(value.stripTrailingZeros().toPlainString());
            }
        });
        return module;
    }
}
