package io.fluxora.platform.auth;

import io.fluxora.common.error.BusinessErrorCode;

/**
 * 认证异常，由 GlobalExceptionHandler 映射为 401 + 安全中文提示。
 * 包含明确的业务错误码，用于区分"密码错误"和"租户停用/过期/删除"等场景。
 */
public class AuthException extends RuntimeException {

    private final BusinessErrorCode errorCode;

    public AuthException(BusinessErrorCode errorCode) {
        super(errorCode.getDefaultUserMessage());
        this.errorCode = errorCode;
    }

    public BusinessErrorCode getErrorCode() {
        return errorCode;
    }
}
