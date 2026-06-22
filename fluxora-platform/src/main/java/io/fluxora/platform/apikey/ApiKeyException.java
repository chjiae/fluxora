package io.fluxora.platform.apikey;

import io.fluxora.common.error.BusinessErrorCode;

/**
 * API Key 业务异常，结构镜像 {@link io.fluxora.platform.identity.MemberException}。
 * 仅暴露 {@link BusinessErrorCode}；GlobalExceptionHandler 据此映射 HTTP 状态码与
 * 用户可理解的中文文案。
 */
public class ApiKeyException extends RuntimeException {
    private final BusinessErrorCode errorCode;

    public ApiKeyException(BusinessErrorCode errorCode) {
        super(errorCode.getDefaultUserMessage());
        this.errorCode = errorCode;
    }

    public ApiKeyException(BusinessErrorCode errorCode, String detail) {
        super(detail);
        this.errorCode = errorCode;
    }

    public BusinessErrorCode getErrorCode() {
        return errorCode;
    }
}
