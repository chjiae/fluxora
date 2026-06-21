package io.fluxora.common.error;

/**
 * 安全错误响应结构。
 * 包含可供前端程序识别的业务错误码（code）、用户可理解的中文提示（message）和可选的追踪标识（traceId）。
 * 绝不包含异常堆栈、SQL、密码哈希或内部配置等敏感信息。
 */
public record ErrorResponse(String code, String message, String traceId) {

    public static ErrorResponse of(BusinessErrorCode errorCode) {
        return new ErrorResponse(errorCode.name(), errorCode.getDefaultUserMessage(), null);
    }

    public static ErrorResponse of(BusinessErrorCode errorCode, String userMessage) {
        return new ErrorResponse(errorCode.name(), userMessage, null);
    }

    public static ErrorResponse of(BusinessErrorCode errorCode, String userMessage, String traceId) {
        return new ErrorResponse(errorCode.name(), userMessage, traceId);
    }
}
