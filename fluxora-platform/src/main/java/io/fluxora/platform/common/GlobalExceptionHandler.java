package io.fluxora.platform.common;

import io.fluxora.common.error.BusinessErrorCode;
import io.fluxora.common.error.ErrorResponse;
import io.fluxora.platform.apikey.ApiKeyException;
import io.fluxora.platform.auth.AuthException;
import io.fluxora.platform.card.CardException;
import io.fluxora.platform.credit.CreditException;
import io.fluxora.platform.identity.MemberException;
import io.fluxora.platform.tenant.TenantException;
import io.fluxora.platform.tenant.TenantService;
import io.fluxora.platform.upstream.provider.ProviderException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.authorization.AuthorizationDeniedException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.http.converter.HttpMessageNotReadableException;

/**
 * 全局异常映射。
 * 将所有异常统一转换为安全的中文错误响应，绝不对用户泄露异常堆栈、SQL 或内部状态。
 * 
 * 异常映射策略：
 * - AuthorizationDeniedException → 403（@PreAuthorize 权限拒绝）
 * - AuthException              → 401（登录认证失败）
 * - AuthTenantException        → 401（租户状态异常：停用/过期/删除）
 * - TenantException            → 400 或 403（业务规则校验，如租户码重复、自营保护）
 * - MemberException            → 400 / 403 / 404（成员管理业务规则）
 * - ProviderException          → 400 / 403 / 404（上游配置业务规则：厂商/地址/通道/凭证）
 * - IllegalArgumentException  → 400（参数校验失败）
 * - 其他 Exception             → 500（服务内部异常，记录日志后返回通用提示）
 */
@RestControllerAdvice
public class GlobalExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);

    @ExceptionHandler(AuthorizationDeniedException.class)
    public ResponseEntity<ErrorResponse> handleAuthorizationDenied(AuthorizationDeniedException ex) {
        return ResponseEntity.status(HttpStatus.FORBIDDEN)
                .body(ErrorResponse.of(BusinessErrorCode.ACCESS_DENIED));
    }

    @ExceptionHandler(AuthException.class)
    public ResponseEntity<ErrorResponse> handleAuthException(AuthException ex) {
        return ResponseEntity.status(HttpStatus.UNAUTHORIZED)
                .body(ErrorResponse.of(ex.getErrorCode()));
    }

    @ExceptionHandler(TenantService.AuthTenantException.class)
    public ResponseEntity<ErrorResponse> handleAuthTenantException(TenantService.AuthTenantException ex) {
        return ResponseEntity.status(HttpStatus.UNAUTHORIZED)
                .body(ErrorResponse.of(ex.getErrorCode()));
    }

    @ExceptionHandler(TenantException.class)
    public ResponseEntity<ErrorResponse> handleTenantException(TenantException ex) {
        HttpStatus status = ex.getErrorCode() == BusinessErrorCode.ACCESS_DENIED
                ? HttpStatus.FORBIDDEN : HttpStatus.BAD_REQUEST;
        // 仅使用受控错误码的默认安全文案，不暴露构造时传入的动态消息
        return ResponseEntity.status(status)
                .body(ErrorResponse.of(ex.getErrorCode()));
    }

    /**
     * 成员管理异常映射。
     * 不同错误码映射到不同 HTTP 状态，便于前端 axios 区分；
     * 用户始终看到错误码默认的安全中文文案，不暴露 ex.getMessage() 的动态内容。
     */
    @ExceptionHandler(MemberException.class)
    public ResponseEntity<ErrorResponse> handleMemberException(MemberException ex) {
        BusinessErrorCode code = ex.getErrorCode();
        HttpStatus status = switch (code) {
            case MEMBER_NOT_FOUND, RESOURCE_NOT_FOUND -> HttpStatus.NOT_FOUND;
            case CROSS_TENANT_ACCESS_DENIED, ACCESS_DENIED -> HttpStatus.FORBIDDEN;
            default -> HttpStatus.BAD_REQUEST;
        };
        return ResponseEntity.status(status).body(ErrorResponse.of(code));
    }

    /**
     * ApiKey 异常映射状态码：NOT_FOUND → 404；ACCESS_DENIED → 403；其余 → 400。
     * 与 MemberException 映射策略一致。
     */
    @ExceptionHandler(ApiKeyException.class)
    public ResponseEntity<ErrorResponse> handleApiKeyException(ApiKeyException ex) {
        BusinessErrorCode code = ex.getErrorCode();
        HttpStatus status = switch (code) {
            case API_KEY_NOT_FOUND, RESOURCE_NOT_FOUND -> HttpStatus.NOT_FOUND;
            case CROSS_TENANT_ACCESS_DENIED, ACCESS_DENIED -> HttpStatus.FORBIDDEN;
            default -> HttpStatus.BAD_REQUEST;
        };
        return ResponseEntity.status(status).body(ErrorResponse.of(code));
    }

    /**
     * 额度异常映射：NOT_FOUND/ACCOUNT_NOT_FOUND → 404；ACCESS_DENIED → 403；其余 → 400。
     */
    @ExceptionHandler(CreditException.class)
    public ResponseEntity<ErrorResponse> handleCreditException(CreditException ex) {
        BusinessErrorCode code = ex.getErrorCode();
        HttpStatus status = switch (code) {
            case RESOURCE_NOT_FOUND, CREDIT_ACCOUNT_NOT_FOUND -> HttpStatus.NOT_FOUND;
            case CROSS_TENANT_ACCESS_DENIED, ACCESS_DENIED -> HttpStatus.FORBIDDEN;
            default -> HttpStatus.BAD_REQUEST;
        };
        return ResponseEntity.status(status).body(ErrorResponse.of(code));
    }

    /**
     * 卡密异常映射：NOT_FOUND→404，ACCESS_DENIED→403，其余→400。
     */
    @ExceptionHandler(CardException.class)
    public ResponseEntity<ErrorResponse> handleCardException(CardException ex) {
        BusinessErrorCode code = ex.getErrorCode();
        HttpStatus status = switch (code) {
            case CARD_NOT_FOUND, CARD_BATCH_NOT_FOUND, RESOURCE_NOT_FOUND -> HttpStatus.NOT_FOUND;
            case CARD_CROSS_TENANT_REDEEM, CROSS_TENANT_ACCESS_DENIED, ACCESS_DENIED -> HttpStatus.FORBIDDEN;
            default -> HttpStatus.BAD_REQUEST;
        };
        return ResponseEntity.status(status).body(ErrorResponse.of(code));
    }

    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<ErrorResponse> handleIllegalArgument(IllegalArgumentException ex) {
        log.warn("参数校验失败", ex);
        // 不返回 ex.getMessage()，其可能包含日期解析异常等原始技术文本
        return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                .body(ErrorResponse.of(BusinessErrorCode.VALIDATION_ERROR));
    }

    /** JSON 类型、格式或结构不符合请求 DTO 时返回安全的参数错误，不暴露反序列化器内部信息。 */
    @ExceptionHandler(HttpMessageNotReadableException.class)
    public ResponseEntity<ErrorResponse> handleUnreadableRequest(HttpMessageNotReadableException ex) {
        log.warn("请求体格式不正确");
        return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                .body(ErrorResponse.of(BusinessErrorCode.VALIDATION_ERROR));
    }

    /**
     * 上游配置异常映射。
     * NOT_FOUND 类 → 404；ACCESS_DENIED / CROSS_TENANT / 共享只读 → 403；其余业务校验 → 400。
     * 始终返回错误码默认安全文案，不暴露 ex.getMessage() 的动态构造内容。
     */
    @ExceptionHandler(ProviderException.class)
    public ResponseEntity<ErrorResponse> handleUpstreamException(ProviderException ex) {
        BusinessErrorCode code = ex.getErrorCode();
        HttpStatus status = switch (code) {
            case RESOURCE_NOT_FOUND -> HttpStatus.NOT_FOUND;
            case ACCESS_DENIED, CROSS_TENANT_ACCESS_DENIED, UPSTREAM_SHARED_READONLY ->
                    HttpStatus.FORBIDDEN;
            default -> HttpStatus.BAD_REQUEST;
        };
        return ResponseEntity.status(status).body(ErrorResponse.of(code));
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<ErrorResponse> handleGeneralException(Exception ex) {
        log.error("服务内部异常", ex);
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                .body(ErrorResponse.of(BusinessErrorCode.INTERNAL_ERROR));
    }
}
