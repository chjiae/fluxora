package io.fluxora.platform.model.discovery;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * SSRF 守卫单元测试：覆盖典型危险地址与公共可用地址的边界。
 */
class ModelDiscoverySsrfGuardTest {

    @Test
    void shouldAllowPublicHttpsUrl() {
        // example.com 公共域名应被允许（DNS 解析成功且非内网）
        // 注：testcontainers/maven 环境必须有外网解析，否则被 java.net.UnknownHostException 拒绝；
        // 该断言只校验「不抛 不允许访问内部地址」的语义。
        try {
            var uri = ModelDiscoverySsrfGuard.validate("https://example.com/v1");
            assertThat(uri.getHost()).isEqualTo("example.com");
        } catch (IllegalArgumentException ex) {
            // 离线环境只能放过
            assertThat(ex.getMessage()).contains("无法解析");
        }
    }

    @Test
    void shouldRejectLocalhost() {
        assertThatThrownBy(() -> ModelDiscoverySsrfGuard.validate("http://localhost/v1"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("内部地址");
    }

    @Test
    void shouldRejectLoopbackIp() {
        assertThatThrownBy(() -> ModelDiscoverySsrfGuard.validate("http://127.0.0.1/v1"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("内部地址");
    }

    @Test
    void shouldRejectPrivateRange() {
        assertThatThrownBy(() -> ModelDiscoverySsrfGuard.validate("http://10.0.0.1/v1"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("内部地址");
        assertThatThrownBy(() -> ModelDiscoverySsrfGuard.validate("http://192.168.1.1/v1"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("内部地址");
        assertThatThrownBy(() -> ModelDiscoverySsrfGuard.validate("http://172.16.0.1/v1"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("内部地址");
    }

    @Test
    void shouldRejectAwsMetadataAddress() {
        assertThatThrownBy(() -> ModelDiscoverySsrfGuard.validate("http://169.254.169.254/"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("内部地址");
    }

    @Test
    void shouldRejectInternalDomainSuffix() {
        assertThatThrownBy(() -> ModelDiscoverySsrfGuard.validate("http://api.internal/v1"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("内部地址");
        assertThatThrownBy(() -> ModelDiscoverySsrfGuard.validate("http://api.local/v1"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("内部地址");
    }

    @Test
    void shouldRejectNonHttpScheme() {
        assertThatThrownBy(() -> ModelDiscoverySsrfGuard.validate("file:///etc/passwd"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("协议");
        assertThatThrownBy(() -> ModelDiscoverySsrfGuard.validate("ftp://example.com/"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("协议");
    }

    @Test
    void shouldRejectEmptyOrMalformedUrl() {
        assertThatThrownBy(() -> ModelDiscoverySsrfGuard.validate(null))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> ModelDiscoverySsrfGuard.validate(""))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> ModelDiscoverySsrfGuard.validate("not a url"))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
