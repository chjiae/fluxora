package io.fluxora.platform.security;

import io.fluxora.common.error.ErrorResponse;
import io.fluxora.common.error.BusinessErrorCode;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;

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
