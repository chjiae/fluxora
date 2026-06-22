package io.fluxora.platform.upstream.provider;
import io.fluxora.common.error.BusinessErrorCode;
/** 上游领域异常只携带安全业务错误码与中文文案。 */
public class ProviderException extends RuntimeException {
    private final BusinessErrorCode errorCode;
    public ProviderException(BusinessErrorCode errorCode, String message) { super(message); this.errorCode = errorCode; }
    public BusinessErrorCode getErrorCode() { return errorCode; }
}
