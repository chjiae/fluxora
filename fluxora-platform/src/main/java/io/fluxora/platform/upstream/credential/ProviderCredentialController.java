package io.fluxora.platform.upstream.credential;

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
import io.fluxora.platform.upstream.credential.dto.CredentialImportRequest;
import io.fluxora.platform.upstream.credential.dto.CredentialImportResult;
import io.fluxora.platform.upstream.credential.dto.CreateCredentialRequest;
import io.fluxora.platform.upstream.credential.dto.ProviderCredentialStats;
import io.fluxora.platform.upstream.credential.dto.ProviderCredentialSummary;
import io.fluxora.platform.upstream.credential.dto.ReplaceCredentialRequest;
import io.fluxora.platform.upstream.credential.dto.UpdateCredentialRequest;
import io.fluxora.platform.upstream.dto.UpstreamPage;
import io.fluxora.platform.runtime.availability.UpstreamRuntimeFailureService;

/**
 * 上游凭证 REST 接口。
 *
 * 所有响应只返回脱敏元数据；明文仅在创建、替换与导入请求中由用户输入，处理后不保留。
 * 通道归属与租户隔离由 {@link ProviderCredentialService} 通过通道可见性校验强制。
 */
@RestController
@RequestMapping("/api/provider-credentials")
public class ProviderCredentialController {

    private final ProviderCredentialService service;
    private final UpstreamRuntimeFailureService runtimeFailureService;

    public ProviderCredentialController(ProviderCredentialService service,
                                        UpstreamRuntimeFailureService runtimeFailureService) {
        this.service = service;
        this.runtimeFailureService = runtimeFailureService;
    }

    @GetMapping
    @PreAuthorize("hasAuthority('PERM_UPSTREAM_READ')")
    public ResponseEntity<ApiResponse<UpstreamPage<ProviderCredentialSummary>>> list(
            @RequestParam Long providerChannelId,
            @AuthenticationPrincipal UserAccount user, Authentication auth,
            @RequestParam(defaultValue = "") String keyword,
            @RequestParam(defaultValue = "") String maskedValue,
            @RequestParam(required = false) Boolean enabled,
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "20") int size) {
        return ResponseEntity.ok(ApiResponse.success(
                service.list(providerChannelId, user, auth, keyword, maskedValue, enabled, page, size)));
    }

    @GetMapping("/stats")
    @PreAuthorize("hasAuthority('PERM_UPSTREAM_READ')")
    public ResponseEntity<ApiResponse<ProviderCredentialStats>> stats(
            @RequestParam Long providerChannelId,
            @AuthenticationPrincipal UserAccount user, Authentication auth) {
        return ResponseEntity.ok(ApiResponse.success(service.stats(providerChannelId, user, auth)));
    }

    @GetMapping("/{id}")
    @PreAuthorize("hasAuthority('PERM_UPSTREAM_READ')")
    public ResponseEntity<ApiResponse<ProviderCredentialSummary>> detail(
            @PathVariable Long id, @AuthenticationPrincipal UserAccount user, Authentication auth) {
        return ResponseEntity.ok(ApiResponse.success(service.detail(id, user, auth)));
    }

    @PostMapping
    @PreAuthorize("hasAuthority('PERM_UPSTREAM_CREATE')")
    public ResponseEntity<ApiResponse<ProviderCredentialSummary>> create(
            @RequestBody CreateCredentialRequest req,
            @AuthenticationPrincipal UserAccount user, Authentication auth) {
        return ResponseEntity.ok(ApiResponse.success(service.create(
                new ProviderCredentialService.CreateFields(req.providerChannelId(), req.plaintext(),
                        req.name(), req.priority(), req.weight(), req.remark(), req.authType()), user, auth)));
    }

    /** 把已有凭证绑定到同租户同 Provider 的另一通道；响应不返回密文、指纹或上游认证材料。 */
    @PostMapping("/{id}/bindings")
    @PreAuthorize("hasAuthority('PERM_UPSTREAM_UPDATE')")
    public ResponseEntity<ApiResponse<Void>> bind(@PathVariable Long id, @RequestBody CredentialBindingRequest req,
                                                    @AuthenticationPrincipal UserAccount user, Authentication auth) {
        service.bindExisting(id, req.providerChannelId(), user, auth);
        return ResponseEntity.ok(ApiResponse.success(null));
    }

    @PutMapping("/{id}/bindings/{providerChannelId}/enable")
    @PreAuthorize("hasAuthority('PERM_UPSTREAM_ENABLE')")
    public ResponseEntity<ApiResponse<Void>> enableBinding(@PathVariable Long id, @PathVariable Long providerChannelId,
                                                            @AuthenticationPrincipal UserAccount user, Authentication auth) {
        service.setBindingEnabled(id, providerChannelId, true, user, auth);
        return ResponseEntity.ok(ApiResponse.success(null));
    }

    @PutMapping("/{id}/bindings/{providerChannelId}/disable")
    @PreAuthorize("hasAuthority('PERM_UPSTREAM_DISABLE')")
    public ResponseEntity<ApiResponse<Void>> disableBinding(@PathVariable Long id, @PathVariable Long providerChannelId,
                                                             @AuthenticationPrincipal UserAccount user, Authentication auth) {
        service.setBindingEnabled(id, providerChannelId, false, user, auth);
        return ResponseEntity.ok(ApiResponse.success(null));
    }

    @DeleteMapping("/{id}/bindings/{providerChannelId}")
    @PreAuthorize("hasAuthority('PERM_UPSTREAM_DELETE')")
    public ResponseEntity<ApiResponse<Void>> unbind(@PathVariable Long id, @PathVariable Long providerChannelId,
                                                      @AuthenticationPrincipal UserAccount user, Authentication auth) {
        service.unbind(id, providerChannelId, user, auth);
        return ResponseEntity.ok(ApiResponse.success(null));
    }

    @PutMapping("/{id}")
    @PreAuthorize("hasAuthority('PERM_UPSTREAM_UPDATE')")
    public ResponseEntity<ApiResponse<ProviderCredentialSummary>> update(
            @PathVariable Long id, @RequestBody UpdateCredentialRequest req,
            @AuthenticationPrincipal UserAccount user, Authentication auth) {
        return ResponseEntity.ok(ApiResponse.success(service.updateMetadata(id,
                new ProviderCredentialService.UpdateFields(req.name(), req.priority(), req.weight(), req.remark(), req.authType()),
                user, auth)));
    }

    @PutMapping("/{id}/replace")
    @PreAuthorize("hasAuthority('PERM_UPSTREAM_UPDATE')")
    public ResponseEntity<ApiResponse<ProviderCredentialSummary>> replace(
            @PathVariable Long id, @RequestBody ReplaceCredentialRequest req,
            @AuthenticationPrincipal UserAccount user, Authentication auth) {
        return ResponseEntity.ok(ApiResponse.success(service.replaceSecret(id, req.plaintext(), user, auth)));
    }

    @PutMapping("/{id}/enable")
    @PreAuthorize("hasAuthority('PERM_UPSTREAM_ENABLE')")
    public ResponseEntity<ApiResponse<Void>> enable(
            @PathVariable Long id, @AuthenticationPrincipal UserAccount user, Authentication auth) {
        service.setEnabled(id, true, user, auth);
        return ResponseEntity.ok(ApiResponse.success(null));
    }

    @PutMapping("/{id}/runtime/recover")
    @PreAuthorize("hasRole('PLATFORM_ADMIN')")
    public ResponseEntity<ApiResponse<Void>> recoverRuntimeState(@PathVariable Long id) {
        runtimeFailureService.recoverCredential(id);
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

    @PostMapping("/import")
    @PreAuthorize("hasAuthority('PERM_UPSTREAM_CREATE')")
    public ResponseEntity<ApiResponse<CredentialImportResult>> importCredentials(
            @RequestBody CredentialImportRequest req,
            @AuthenticationPrincipal UserAccount user, Authentication auth) {
        return ResponseEntity.ok(ApiResponse.success(service.importCredentials(req, user, auth)));
    }

    /** 绑定请求仅传目标通道 ID；服务层负责租户、Provider 一致性与现有绑定检查。 */
    public record CredentialBindingRequest(Long providerChannelId) {}
}
