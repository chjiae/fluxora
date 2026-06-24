package io.fluxora.gateway.transport;

import io.fluxora.gateway.GatewayFailure;
import java.net.InetAddress;
import java.net.URI;

/** 受控 Base URL 的 SSRF 边界；本地地址只在 local/test profile 显式放行。 */
public final class UpstreamUrlValidator {
    private UpstreamUrlValidator() {}
    public static String endpoint(String baseUrl, String endpoint, String profile) {
        try {
            URI base = URI.create(baseUrl);
            if (!"http".equalsIgnoreCase(base.getScheme()) && !"https".equalsIgnoreCase(base.getScheme())
                    || base.getHost() == null || base.getQuery() != null || base.getFragment() != null) throw GatewayFailure.runtimeUnavailable();
            boolean local = "local".equals(profile) || "test".equals(profile);
            if (!local && isPrivate(base.getHost())) throw GatewayFailure.runtimeUnavailable();
            String prefix = base.getPath() == null ? "" : base.getPath().replaceAll("/+$", "");
            return new URI(base.getScheme(), base.getAuthority(), prefix + endpoint, null, null).toString();
        } catch (Exception e) { throw GatewayFailure.runtimeUnavailable(); }
    }
    private static boolean isPrivate(String host) throws Exception {
        if (host.equalsIgnoreCase("localhost") || host.endsWith(".local") || host.endsWith(".internal")) return true;
        for (InetAddress a : InetAddress.getAllByName(host)) if (a.isAnyLocalAddress() || a.isLoopbackAddress() || a.isLinkLocalAddress() || a.isSiteLocalAddress() || a.isMulticastAddress()) return true;
        return false;
    }
}
