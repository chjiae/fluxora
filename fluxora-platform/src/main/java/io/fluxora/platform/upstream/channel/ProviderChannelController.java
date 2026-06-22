package io.fluxora.platform.upstream.channel;

import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import io.fluxora.common.response.ApiResponse;
import io.fluxora.platform.identity.entity.UserAccount;
import io.fluxora.platform.upstream.channel.dto.ProviderChannelStats;
import io.fluxora.platform.upstream.channel.dto.ProviderChannelSummary;
import io.fluxora.platform.upstream.dto.UpstreamPage;

/**
 * 租户上游通道接口。
 * 控制器只做协议转换，租户归属、引用可见性与参数边界统一由 {@link ProviderChannelService} 强制。
 */
@RestController
@RequestMapping("/api/provider-channels")
public class ProviderChannelController {

    private final ProviderChannelService service;

    public ProviderChannelController(ProviderChannelService service) {
        this.service = service;
    }

    @GetMapping
    @PreAuthorize("hasAuthority('PERM_UPSTREAM_READ')")
    public ResponseEntity<ApiResponse<UpstreamPage<ProviderChannelSummary>>> list(
            @AuthenticationPrincipal UserAccount user, Authentication auth,
            @RequestParam(required = false) Long tenantId,
            @RequestParam(defaultValue = "") String keyword,
            @RequestParam(required = false) Long providerId,
            @RequestParam(defaultValue = "") String protocol,
            @RequestParam(required = false) Boolean enabled,
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "20") int size) {
        return ResponseEntity.ok(ApiResponse.success(
                service.listChannels(user, auth, tenantId, keyword, providerId, protocol, enabled, page, size)));
    }

    @GetMapping("/stats")
    @PreAuthorize("hasAuthority('PERM_UPSTREAM_READ')")
    public ResponseEntity<ApiResponse<ProviderChannelStats>> stats(
            @AuthenticationPrincipal UserAccount user, Authentication auth,
            @RequestParam(required = false) Long tenantId) {
        return ResponseEntity.ok(ApiResponse.success(service.channelStats(user, auth, tenantId)));
    }

    @GetMapping("/{id}")
    @PreAuthorize("hasAuthority('PERM_UPSTREAM_READ')")
    public ResponseEntity<ApiResponse<ProviderChannelSummary>> detail(
            @PathVariable Long id, @AuthenticationPrincipal UserAccount user, Authentication auth) {
        return ResponseEntity.ok(ApiResponse.success(service.channelDetail(id, user, auth)));
    }

    @PostMapping
    @PreAuthorize("hasAuthority('PERM_UPSTREAM_CREATE')")
    public ResponseEntity<ApiResponse<ProviderChannelSummary>> create(
            @RequestBody ChannelRequest r,
            @AuthenticationPrincipal UserAccount user, Authentication auth) {
        return ResponseEntity.ok(ApiResponse.success(service.create(toEntity(r), r.tenantId(), user, auth)));
    }

    @PutMapping("/{id}")
    @PreAuthorize("hasAuthority('PERM_UPSTREAM_UPDATE')")
    public ResponseEntity<ApiResponse<ProviderChannelSummary>> update(
            @PathVariable Long id, @RequestBody ChannelRequest r,
            @AuthenticationPrincipal UserAccount user, Authentication auth) {
        return ResponseEntity.ok(ApiResponse.success(service.update(id, toEntity(r), user, auth)));
    }

    @PutMapping("/{id}/enable")
    @PreAuthorize("hasAuthority('PERM_UPSTREAM_ENABLE')")
    public ResponseEntity<ApiResponse<Void>> enable(
            @PathVariable Long id, @AuthenticationPrincipal UserAccount user, Authentication auth) {
        service.setEnabled(id, true, user, auth);
        return ResponseEntity.ok(ApiResponse.success(null));
    }

    @PutMapping("/{id}/disable")
    @PreAuthorize("hasAuthority('PERM_UPSTREAM_DISABLE')")
    public ResponseEntity<ApiResponse<Void>> disable(
            @PathVariable Long id, @AuthenticationPrincipal UserAccount user, Authentication auth) {
        service.setEnabled(id, false, user, auth);
        return ResponseEntity.ok(ApiResponse.success(null));
    }

    @DeleteMapping("/{id}")
    @PreAuthorize("hasAuthority('PERM_UPSTREAM_DELETE')")
    public ResponseEntity<ApiResponse<Void>> delete(
            @PathVariable Long id, @AuthenticationPrincipal UserAccount user, Authentication auth) {
        service.delete(id, user, auth);
        return ResponseEntity.ok(ApiResponse.success(null));
    }

    private ProviderChannel toEntity(ChannelRequest r) {
        ProviderChannel c = new ProviderChannel();
        c.setProviderBaseUrlId(r.providerBaseUrlId());
        c.setName(r.name());
        c.setEnabled(r.enabled() == null || r.enabled());
        c.setPriority(r.priority() == null ? 100 : r.priority());
        c.setWeight(r.weight() == null ? 100 : r.weight());
        c.setConnectTimeoutMs(r.connectTimeoutMs() == null ? 5000 : r.connectTimeoutMs());
        c.setReadTimeoutMs(r.readTimeoutMs() == null ? 60000 : r.readTimeoutMs());
        c.setRemark(r.remark());
        return c;
    }

    public record ChannelRequest(Long tenantId, Long providerBaseUrlId, String name, Boolean enabled,
                                 Integer priority, Integer weight, Integer connectTimeoutMs,
                                 Integer readTimeoutMs, String remark) {
    }
}
