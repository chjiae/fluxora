package io.fluxora.platform.common;

import io.fluxora.common.error.BusinessErrorCode;
import io.fluxora.common.error.ErrorResponse;
import io.fluxora.platform.auth.AuthException;
import io.fluxora.platform.tenant.TenantException;
import io.fluxora.platform.tenant.TenantService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.authorization.AuthorizationDeniedException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

/**
 * 全局异常映射。
 * 将所有异常统一转换为安全的中文错误响应，绝不对用户泄露异常堆栈、SQL 或内部状态。
 * 
 * 异常映射策略：
 * - AuthorizationDeniedException → 403（@PreAuthorize 权限拒绝）
 * - AuthException              → 401（登录认证失败）
 * - AuthTenantException        → 401（租户状态异常：停用/过期/删除）
 * - TenantException            → 400 或 403（业务规则校验，如租户码重复、自营保护）
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
        return ResponseEntity.status(status)
                .body(ErrorResponse.of(ex.getErrorCode(), ex.getMessage()));
    }

    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<ErrorResponse> handleIllegalArgument(IllegalArgumentException ex) {
        log.warn("参数校验失败", ex);
        return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                .body(ErrorResponse.of(BusinessErrorCode.VALIDATION_ERROR, ex.getMessage()));
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<ErrorResponse> handleGeneralException(Exception ex) {
        log.error("服务内部异常", ex);
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                .body(ErrorResponse.of(BusinessErrorCode.INTERNAL_ERROR));
    }
}
