package io.fluxora.platform.credit;

import io.fluxora.common.error.BusinessErrorCode;

/**
 * 额度业务异常。结构镜像 {@link io.fluxora.platform.identity.MemberException}。
 */
public class CreditException extends RuntimeException {
    private final BusinessErrorCode errorCode;

    public CreditException(BusinessErrorCode errorCode) {
        super(errorCode.getDefaultUserMessage());
        this.errorCode = errorCode;
    }

    public CreditException(BusinessErrorCode errorCode, String detail) {
        super(detail);
        this.errorCode = errorCode;
    }

    public BusinessErrorCode getErrorCode() {
        return errorCode;
    }
}
