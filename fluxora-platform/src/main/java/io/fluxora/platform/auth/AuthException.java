package io.fluxora.platform.auth;

import io.fluxora.common.error.BusinessErrorCode;

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
