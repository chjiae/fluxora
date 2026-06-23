package io.fluxora.platform.upstream.security;

import java.net.InetAddress;
import java.net.URI;
import java.util.Arrays;

/** 模型发现外部请求的 SSRF 边界：仅公共 HTTP(S) 主机，DNS 全部结果均必须安全。 */
public final class ModelDiscoverySsrfGuard {
    private ModelDiscoverySsrfGuard() {}
    public static URI validate(String rawUrl) {
        URI uri=URI.create(rawUrl);
        if(!"http".equalsIgnoreCase(uri.getScheme())&&!"https".equalsIgnoreCase(uri.getScheme())||uri.getHost()==null) throw new IllegalArgumentException("不支持的模型同步地址");
        String host=uri.getHost().toLowerCase();
        if("localhost".equals(host)||host.endsWith(".localhost")) throw new IllegalArgumentException("不允许访问本地地址");
        try { if(Arrays.stream(InetAddress.getAllByName(host)).anyMatch(ModelDiscoverySsrfGuard::privateAddress)) throw new IllegalArgumentException("不允许访问内部地址"); }
        catch (java.net.UnknownHostException e) { throw new IllegalArgumentException("模型同步地址无法解析"); }
        return uri;
    }
    private static boolean privateAddress(InetAddress a){return a.isAnyLocalAddress()||a.isLoopbackAddress()||a.isLinkLocalAddress()||a.isSiteLocalAddress()||a.isMulticastAddress();}
}
