package io.fluxora.platform.billing;

import tools.jackson.core.JsonParser;
import tools.jackson.core.JsonToken;
import tools.jackson.databind.DeserializationContext;
import tools.jackson.databind.ValueDeserializer;

/**
 * 账务金额 JSON 输入边界：只接受 JSON 字符串，明确拒绝 Number、布尔值与结构化值。
 * 具体精度和正负范围继续由 CnyPrecisionPolicy 与领域服务校验。
 */
public final class DecimalStringDeserializer extends ValueDeserializer<String> {
    @Override
    public String deserialize(JsonParser parser, DeserializationContext context) {
        if (!JsonToken.VALUE_STRING.equals(parser.currentToken())) {
            return (String) context.handleUnexpectedToken(String.class, parser);
        }
        return parser.getString();
    }
}
