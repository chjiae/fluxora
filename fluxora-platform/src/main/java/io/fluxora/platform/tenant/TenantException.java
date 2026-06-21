package io.fluxora.platform.tenant;

import io.fluxora.common.error.BusinessErrorCode;

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
