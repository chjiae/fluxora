package io.fluxora.gateway.transport;

import io.fluxora.gateway.GatewayFailure;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/** 本地 Ollama 白名单只能放行显式主机，不能把整个内网暴露给测试 Profile。 */
class UpstreamUrlValidatorTest {

    @Test
    void shouldAllowExplicitLocalOllamaHostsAndPreserveBasePath() {
        assertEquals("http://127.0.0.1:11435/v1/chat/completions",
                UpstreamUrlValidator.endpoint("http://127.0.0.1:11435", "/v1/chat/completions", "local"));
        assertEquals("http://host.docker.internal:11435/proxy/v1/messages",
                UpstreamUrlValidator.endpoint("http://host.docker.internal:11435/proxy", "/v1/messages", "test"));
    }

    @Test
    void shouldRejectNonWhitelistedPrivateHostsEvenInLocalProfile() {
        assertThrows(GatewayFailure.class,
                () -> UpstreamUrlValidator.endpoint("http://192.168.1.20:11435", "/v1/chat/completions", "local"));
        assertThrows(GatewayFailure.class,
                () -> UpstreamUrlValidator.endpoint("http://169.254.169.254", "/v1/messages", "test"));
    }
}
