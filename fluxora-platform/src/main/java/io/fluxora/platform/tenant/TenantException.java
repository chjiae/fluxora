package io.fluxora.platform.tenant;

import io.fluxora.common.error.BusinessErrorCode;

/**
 * 租户业务异常。
 * 包含业务错误码和用户可读消息，由 GlobalExceptionHandler 统一映射为安全 HTTP 响应。
 * 根据错误码返回不同 HTTP 状态码：ACCESS_DENIED → 403，其他 → 400。
 */
public class TenantException extends RuntimeException {

    private final BusinessErrorCode errorCode;

    public TenantException(BusinessErrorCode errorCode, String userMessage) {
        super(userMessage);
        this.errorCode = errorCode;
    }

    public TenantException(BusinessErrorCode errorCode) {
        this(errorCode, errorCode.getDefaultUserMessage());
    }

    public BusinessErrorCode getErrorCode() {
        return errorCode;
    }
}
