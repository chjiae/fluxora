package io.fluxora.platform.model;

import io.fluxora.common.error.BusinessErrorCode;

/** 模型领域异常只携带安全业务错误码与内部排查日志文本；用户始终看到错误码默认中文文案。 */
public class ModelException extends RuntimeException {
    private final BusinessErrorCode errorCode;

    public ModelException(BusinessErrorCode errorCode, String message) {
        super(message);
        this.errorCode = errorCode;
    }

    public BusinessErrorCode getErrorCode() {
        return errorCode;
    }
}
