package io.fluxora.platform.upstream.provider;

import java.util.List;

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
import io.fluxora.platform.upstream.provider.dto.ProviderBaseUrlStats;
import io.fluxora.platform.upstream.provider.dto.ProviderBaseUrlSummary;

/**
 * 接入基础地址 REST 接口。
 * 所有写操作复用 {@link ProviderService} 的共享/私有作用域与可见性保护；
 * 同厂商同协议同规范化地址的唯一性由服务层预检兜底。
 */
@RestController
@RequestMapping("/api/provider-base-urls")
public class ProviderBaseUrlController {

    private final ProviderService service;

    public ProviderBaseUrlController(ProviderService service) {
        this.service = service;
    }

    @GetMapping
    @PreAuthorize("hasAuthority('PERM_UPSTREAM_READ')")
    public ResponseEntity<ApiResponse<List<ProviderBaseUrlSummary>>> list(
            @RequestParam Long providerId,
            @AuthenticationPrincipal UserAccount user, Authentication auth) {
        return ResponseEntity.ok(ApiResponse.success(service.listBaseUrls(providerId, user, auth)));
    }

    @GetMapping("/stats")
    @PreAuthorize("hasAuthority('PERM_UPSTREAM_READ')")
    public ResponseEntity<ApiResponse<ProviderBaseUrlStats>> stats(
            @RequestParam Long providerId,
            @AuthenticationPrincipal UserAccount user, Authentication auth) {
        return ResponseEntity.ok(ApiResponse.success(service.baseUrlStats(providerId, user, auth)));
    }

    @PostMapping
    @PreAuthorize("hasAuthority('PERM_UPSTREAM_CREATE')")
    public ResponseEntity<ApiResponse<ProviderBaseUrlSummary>> create(
            @RequestBody CreateProviderBaseUrlRequest req,
            @AuthenticationPrincipal UserAccount user, Authentication auth) {
        return ResponseEntity.ok(ApiResponse.success(service.createBaseUrl(
                req.providerId(), req.protocol(), req.baseUrl(), req.displayName(), req.remark(), user, auth)));
    }

    @PutMapping("/{id}")
    @PreAuthorize("hasAuthority('PERM_UPSTREAM_UPDATE')")
    public ResponseEntity<ApiResponse<ProviderBaseUrlSummary>> update(
            @PathVariable Long id, @RequestBody CreateProviderBaseUrlRequest req,
            @AuthenticationPrincipal UserAccount user, Authentication auth) {
        return ResponseEntity.ok(ApiResponse.success(service.updateBaseUrl(
                id, req.protocol(), req.baseUrl(), req.displayName(), req.remark(), user, auth)));
    }

    @PutMapping("/{id}/enable")
    @PreAuthorize("hasAuthority('PERM_UPSTREAM_ENABLE')")
    public ResponseEntity<ApiResponse<Void>> enable(
            @PathVariable Long id, @AuthenticationPrincipal UserAccount user, Authentication auth) {
        service.setBaseUrlEnabled(id, true, user, auth);
        return ResponseEntity.ok(ApiResponse.success(null));
    }

    @PutMapping("/{id}/disable")
    @PreAuthorize("hasAuthority('PERM_UPSTREAM_DISABLE')")
    public ResponseEntity<ApiResponse<Void>> disable(
            @PathVariable Long id, @AuthenticationPrincipal UserAccount user, Authentication auth) {
        service.setBaseUrlEnabled(id, false, user, auth);
        return ResponseEntity.ok(ApiResponse.success(null));
    }

    @DeleteMapping("/{id}")
    @PreAuthorize("hasAuthority('PERM_UPSTREAM_DELETE')")
    public ResponseEntity<ApiResponse<Void>> delete(
            @PathVariable Long id, @AuthenticationPrincipal UserAccount user, Authentication auth) {
        service.deleteBaseUrl(id, user, auth);
        return ResponseEntity.ok(ApiResponse.success(null));
    }

    public record CreateProviderBaseUrlRequest(Long providerId, String protocol, String baseUrl,
                                               String displayName, String remark) {
    }
}
