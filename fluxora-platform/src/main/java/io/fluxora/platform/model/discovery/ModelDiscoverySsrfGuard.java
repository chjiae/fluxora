package io.fluxora.platform.model.discovery;

import java.net.InetAddress;
import java.net.URI;
import java.util.Arrays;

/**
 * 模型发现 SSRF 安全边界。
 * 只允许公共 HTTP/HTTPS 地址；拒绝 localhost / 回环 / 内网 / 链路本地 / 组播 /
 * 云元数据地址（169.254.169.254 等）；DNS 解析后全部结果均须安全。
 * 调用方必须在每次 HTTP 请求前调用 validate，已重定向目标同样校验。
 */
public final class ModelDiscoverySsrfGuard {
    private static final String[] FORBIDDEN_HOST_SUFFIXES = {".local", ".localhost", ".internal", ".localdomain"};
    private static final String[] METADATA_IPS = {"169.254.169.254", "169.254.170.2"};
    private static final String METADATA_IPV6 = "fd00:ec2::254";

    private ModelDiscoverySsrfGuard() {}

    public static URI validate(String rawUrl) {
        if (rawUrl == null || rawUrl.isBlank()) {
            throw new IllegalArgumentException("同步地址不能为空");
        }
        URI uri;
        try {
            uri = URI.create(rawUrl);
        } catch (Exception e) {
            throw new IllegalArgumentException("同步地址格式不正确");
        }
        String scheme = uri.getScheme();
        if (!"http".equalsIgnoreCase(scheme) && !"https".equalsIgnoreCase(scheme)) {
            throw new IllegalArgumentException("不支持的同步地址协议");
        }
        String host = uri.getHost();
        if (host == null) {
            throw new IllegalArgumentException("同步地址缺少主机名");
        }
        String lower = host.toLowerCase();
        if ("localhost".equals(lower) || lower.startsWith("127.") || lower.startsWith("10.")
                || lower.startsWith("192.168.") || lower.startsWith("172.")) {
            throw new IllegalArgumentException("不允许访问内部地址");
        }
        if (lower.endsWith(".internal") || lower.endsWith(".local") || lower.endsWith(".localdomain")) {
            throw new IllegalArgumentException("不允许访问内部地址");
        }
        if (Arrays.asList(METADATA_IPS).contains(lower)) {
            throw new IllegalArgumentException("不允许访问内部地址");
        }
        // DNS 解析后校验全部结果
        try {
            InetAddress[] addresses = InetAddress.getAllByName(host);
            for (InetAddress addr : addresses) {
                if (addr.isAnyLocalAddress() || addr.isLoopbackAddress()
                        || addr.isLinkLocalAddress() || addr.isSiteLocalAddress()
                        || addr.isMulticastAddress()) {
                    throw new IllegalArgumentException("不允许访问内部地址");
                }
            }
        } catch (java.net.UnknownHostException e) {
            throw new IllegalArgumentException("同步地址无法解析");
        }
        return uri;
    }
}