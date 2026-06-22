package io.fluxora.platform.card;

import io.fluxora.common.error.BusinessErrorCode;

/** 卡密业务异常，结构镜像 ApiKeyException / CreditException */
public class CardException extends RuntimeException {
    private final BusinessErrorCode errorCode;
    public CardException(BusinessErrorCode errorCode) {
        super(errorCode.getDefaultUserMessage()); this.errorCode = errorCode;
    }
    public CardException(BusinessErrorCode errorCode, String detail) {
        super(detail); this.errorCode = errorCode;
    }
    public BusinessErrorCode getErrorCode() { return errorCode; }
}