package io.fluxora.platform.security;

import io.fluxora.common.error.ErrorResponse;
import io.fluxora.common.error.BusinessErrorCode;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;

/**
 * Spring Security 过滤器层的安全错误响应工具。
 * 在 Servlet Filter 中直接写入 JSON 响应，绕过 Spring MVC 的异常处理链路。
 * 确保认证入口和权限拒绝返回安全的中文提示，不泄露技术细节。
 */
public final class SecurityExceptionHandler {

    private static final ObjectMapper objectMapper = new ObjectMapper();

    private SecurityExceptionHandler() {}

    public static void writeErrorResponse(HttpServletResponse response, int httpStatus,
                                          BusinessErrorCode errorCode) throws IOException {
        response.setStatus(httpStatus);
        response.setContentType("application/json;charset=UTF-8");
        ErrorResponse body = ErrorResponse.of(errorCode);
        response.getWriter().write(objectMapper.writeValueAsString(body));
    }

    public static void writeErrorResponse(HttpServletResponse response, int httpStatus,
                                          BusinessErrorCode errorCode, String userMessage) throws IOException {
        response.setStatus(httpStatus);
        response.setContentType("application/json;charset=UTF-8");
        ErrorResponse body = ErrorResponse.of(errorCode, userMessage);
        response.getWriter().write(objectMapper.writeValueAsString(body));
    }
}
