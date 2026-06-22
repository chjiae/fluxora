package io.fluxora.platform.upstream;

import java.net.URI;
import java.net.URISyntaxException;

import org.springframework.stereotype.Component;

/**
 * 接入基础 URL 规范化器。
 * 后续网关负责拼接业务接口路径，因此本层拒绝 chat/completions、messages 等具体端点，避免保存不可复用地址。
 */
@Component
public class ProviderBaseUrlNormalizer {

    public String normalize(String rawValue) {
        if (rawValue == null || rawValue.isBlank()) {
            throw new IllegalArgumentException("请输入有效的 HTTP 或 HTTPS 接入基础地址");
        }
        try {
            URI uri = new URI(rawValue.trim());
            if (!"http".equalsIgnoreCase(uri.getScheme()) && !"https".equalsIgnoreCase(uri.getScheme())
                    || uri.getHost() == null || uri.getQuery() != null || uri.getFragment() != null) {
                throw invalidUrl();
            }
            String path = uri.getPath() == null ? "" : uri.getPath().replaceAll("/+$", "");
            String lowerPath = path.toLowerCase();
            if (lowerPath.endsWith("/chat/completions") || lowerPath.endsWith("/messages")) {
                throw new IllegalArgumentException("请输入接入基础地址，不要填写具体业务接口路径");
            }
            return new URI(uri.getScheme().toLowerCase(), null, uri.getHost().toLowerCase(), uri.getPort(),
                    path.isEmpty() ? null : path, null, null).toString();
        } catch (URISyntaxException exception) {
            throw invalidUrl();
        }
    }

    private IllegalArgumentException invalidUrl() {
        return new IllegalArgumentException("请输入有效的 HTTP 或 HTTPS 接入基础地址");
    }
}
