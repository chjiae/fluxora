package io.fluxora.common.response;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertFalse;

class ApiResponseTest {

    @Test
    void successShouldContainDataWithoutMessage() {
        ApiResponse<String> response = ApiResponse.success("ready");

        assertTrue(response.success());
        assertEquals("ready", response.data());
        assertNull(response.message());
    }

    @Test
    void failureShouldContainMessageWithoutData() {
        ApiResponse<Object> response = ApiResponse.failure("服务暂不可用");

        assertFalse(response.success());
        assertNull(response.data());
        assertEquals("服务暂不可用", response.message());
    }
}
