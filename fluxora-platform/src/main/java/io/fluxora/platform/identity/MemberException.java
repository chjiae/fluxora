package io.fluxora.platform.identity;

import io.fluxora.common.error.BusinessErrorCode;

/**
 * 成员管理业务异常。
 *
 * 结构镜像 {@link io.fluxora.platform.tenant.TenantException}：
 *   - 关联 {@link BusinessErrorCode}，由 GlobalExceptionHandler 统一映射 HTTP 状态码与
 *     用户可理解中文文案。
 *   - 仅返回错误码对应的默认安全文案，禁止把构造时传入的动态消息暴露给前端。
 */
public class MemberException extends RuntimeException {
    private final BusinessErrorCode errorCode;

    public MemberException(BusinessErrorCode errorCode) {
        super(errorCode.getDefaultUserMessage());
        this.errorCode = errorCode;
    }

    public MemberException(BusinessErrorCode errorCode, String detail) {
        super(detail);
        this.errorCode = errorCode;
    }

    public BusinessErrorCode getErrorCode() {
        return errorCode;
    }
}
