package io.fluxora.platform.upstream;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 接入基础 URL 规范化器单元测试。
 * 覆盖协议、域名、公共路径保留、末尾斜杠去除、query/fragment 拒绝与业务接口路径拒绝。
 */
class ProviderBaseUrlNormalizerTest {

    private final ProviderBaseUrlNormalizer normalizer = new ProviderBaseUrlNormalizer();

    @Test
    void shouldNormalizeAndTrimTrailingSlashes() {
        assertThat(normalizer.normalize("https://api.example.com/v1///")).isEqualTo("https://api.example.com/v1");
        assertThat(normalizer.normalize("https://api.example.com")).isEqualTo("https://api.example.com");
        assertThat(normalizer.normalize("https://api.example.com/anthropic/")).isEqualTo("https://api.example.com/anthropic");
    }

    @Test
    void shouldPreserveCommonPathSegments() {
        // /v1、/anthropic 等公共路径必须保留，由后续网关拼接业务接口
        assertThat(normalizer.normalize("https://api.example.com/v1")).isEqualTo("https://api.example.com/v1");
        assertThat(normalizer.normalize("https://api.example.com/anthropic")).isEqualTo("https://api.example.com/anthropic");
    }

    @Test
    void shouldRejectBusinessInterfacePaths() {
        assertThatThrownBy(() -> normalizer.normalize("https://api.example.com/v1/chat/completions"))
                .hasMessageContaining("接入基础地址");
        assertThatThrownBy(() -> normalizer.normalize("https://api.example.com/v1/messages"))
                .hasMessageContaining("接入基础地址");
    }

    @Test
    void shouldRejectNonHttpSchemes() {
        assertThatThrownBy(() -> normalizer.normalize("ftp://api.example.com/v1"))
                .hasMessageContaining("HTTP 或 HTTPS");
        assertThatThrownBy(() -> normalizer.normalize("api.example.com/v1"))
                .hasMessageContaining("HTTP 或 HTTPS");
    }

    @Test
    void shouldRejectQueryAndFragment() {
        assertThatThrownBy(() -> normalizer.normalize("https://api.example.com/v1?token=x"))
                .hasMessageContaining("HTTP 或 HTTPS");
        assertThatThrownBy(() -> normalizer.normalize("https://api.example.com/v1#section"))
                .hasMessageContaining("HTTP 或 HTTPS");
    }

    @Test
    void shouldNormalizeSchemeAndHostCase() {
        assertThat(normalizer.normalize("HTTPS://API.Example.COM/v1")).isEqualTo("https://api.example.com/v1");
    }
}
