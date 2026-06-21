package io.fluxora.common.error;

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
