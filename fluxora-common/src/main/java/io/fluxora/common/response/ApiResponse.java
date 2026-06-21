package io.fluxora.common.response;

/**
 * 控制面与数据面共用的最小响应契约，避免公共模块依赖具体业务模型。
 */
public record ApiResponse<T>(boolean success, T data, String message) {

    public static <T> ApiResponse<T> success(T data) {
        return new ApiResponse<>(true, data, null);
    }

    public static <T> ApiResponse<T> failure(String message) {
        return new ApiResponse<>(false, null, message);
    }
}
