package io.fluxora.platform.apikey;

import io.fluxora.common.response.ApiResponse;
import io.fluxora.platform.apikey.dto.ApiKeyPageResponse;
import io.fluxora.platform.apikey.dto.ApiKeyQuery;
import io.fluxora.platform.apikey.dto.ApiKeyStats;
import io.fluxora.platform.apikey.dto.ApiKeySummary;
import io.fluxora.platform.apikey.dto.CreateApiKeyRequest;
import io.fluxora.platform.apikey.dto.CreatedApiKeyResponse;
import io.fluxora.platform.apikey.dto.UpdateApiKeyRequest;
import io.fluxora.platform.identity.entity.UserAccount;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * API Key REST 控制器。
 *
 * 三组入口：
 *   - {@code /api/api-keys*}            ：当前用户作用域（service 强制 SELF scope）
 *   - {@code /api/tenant/{id}/api-keys*}：租户作用域（租户管理员或平台管理员）
 *   - {@code /api/admin/api-keys*}      ：平台全局（仅平台管理员）
 *
 * 所有业务规则与跨租户校验由 {@link ApiKeyService} 承载；@PreAuthorize 仅做粗粒度网关。
 */
@RestController
public class ApiKeyController {

    private final ApiKeyService apiKeyService;

    public ApiKeyController(ApiKeyService apiKeyService) {
        this.apiKeyService = apiKeyService;
    }

    // ============================================================
    // 自身路径（普通用户 / 租户管理员 / 平台管理员皆可登录使用，
    // 但 service 内 PLATFORM 用户会被 SELF scope 拒绝）
    // ============================================================

    @GetMapping("/api/api-keys")
    @PreAuthorize("hasAuthority('PERM_API_KEY_SELF_MANAGE')")
    public ResponseEntity<ApiResponse<ApiKeyPageResponse>> listSelf(
            @AuthenticationPrincipal UserAccount currentUser,
            @RequestParam(required = false) String keyword,
            @RequestParam(required = false) String status,
            @RequestParam(required = false) Integer page,
            @RequestParam(required = false) Integer size) {
        ApiKeyQuery q = new ApiKeyQuery(keyword, status, null, null, page, size);
        return ResponseEntity.ok(ApiResponse.success(
                apiKeyService.listKeys(currentUser, ApiKeyService.Scope.SELF, q)));
    }

    @GetMapping("/api/api-keys/stats")
    @PreAuthorize("hasAuthority('PERM_API_KEY_SELF_MANAGE')")
    public ResponseEntity<ApiResponse<ApiKeyStats>> statsSelf(
            @AuthenticationPrincipal UserAccount currentUser) {
        return ResponseEntity.ok(ApiResponse.success(
                apiKeyService.getStats(currentUser, ApiKeyService.Scope.SELF)));
    }

    @PostMapping("/api/api-keys")
    @PreAuthorize("hasAuthority('PERM_API_KEY_SELF_MANAGE')")
    public ResponseEntity<ApiResponse<CreatedApiKeyResponse>> createSelf(
            @RequestBody CreateApiKeyRequest req,
            @AuthenticationPrincipal UserAccount currentUser) {
        // SELF 路径强制 routeTenantId = null + 忽略 req.forUserId
        return ResponseEntity.ok(ApiResponse.success(
                apiKeyService.createKey(currentUser, null,
                        new CreateApiKeyRequest(req.name(), null, req.expireAt()))));
    }

    @GetMapping("/api/api-keys/{id}")
    @PreAuthorize("hasAnyAuthority('PERM_API_KEY_SELF_MANAGE','PERM_API_KEY_TENANT_MANAGE','PERM_API_KEY_CROSS_TENANT_MANAGE')")
    public ResponseEntity<ApiResponse<ApiKeySummary>> detail(
            @PathVariable Long id,
            @AuthenticationPrincipal UserAccount currentUser) {
        return ResponseEntity.ok(ApiResponse.success(apiKeyService.getKey(currentUser, id)));
    }

    @PutMapping("/api/api-keys/{id}")
    @PreAuthorize("hasAnyAuthority('PERM_API_KEY_SELF_MANAGE','PERM_API_KEY_TENANT_MANAGE','PERM_API_KEY_CROSS_TENANT_MANAGE')")
    public ResponseEntity<ApiResponse<ApiKeySummary>> update(
            @PathVariable Long id,
            @RequestBody UpdateApiKeyRequest req,
            @AuthenticationPrincipal UserAccount currentUser) {
        return ResponseEntity.ok(ApiResponse.success(apiKeyService.updateKey(currentUser, id, req)));
    }

    @PutMapping("/api/api-keys/{id}/enable")
    @PreAuthorize("hasAnyAuthority('PERM_API_KEY_SELF_MANAGE','PERM_API_KEY_TENANT_MANAGE','PERM_API_KEY_CROSS_TENANT_MANAGE')")
    public ResponseEntity<ApiResponse<ApiKeySummary>> enable(
            @PathVariable Long id,
            @AuthenticationPrincipal UserAccount currentUser) {
        return ResponseEntity.ok(ApiResponse.success(apiKeyService.enableKey(currentUser, id)));
    }

    @PutMapping("/api/api-keys/{id}/disable")
    @PreAuthorize("hasAnyAuthority('PERM_API_KEY_SELF_MANAGE','PERM_API_KEY_TENANT_MANAGE','PERM_API_KEY_CROSS_TENANT_MANAGE')")
    public ResponseEntity<ApiResponse<ApiKeySummary>> disable(
            @PathVariable Long id,
            @AuthenticationPrincipal UserAccount currentUser) {
        return ResponseEntity.ok(ApiResponse.success(apiKeyService.disableKey(currentUser, id)));
    }

    @DeleteMapping("/api/api-keys/{id}")
    @PreAuthorize("hasAnyAuthority('PERM_API_KEY_SELF_MANAGE','PERM_API_KEY_TENANT_MANAGE','PERM_API_KEY_CROSS_TENANT_MANAGE')")
    public ResponseEntity<ApiResponse<Void>> delete(
            @PathVariable Long id,
            @AuthenticationPrincipal UserAccount currentUser) {
        apiKeyService.deleteKey(currentUser, id);
        return ResponseEntity.ok(ApiResponse.success(null));
    }

    // ============================================================
    // 租户路径（租户管理员 / 平台管理员）
    // ============================================================

    @GetMapping("/api/tenant/{tenantId}/api-keys")
    @PreAuthorize("hasAnyAuthority('PERM_API_KEY_TENANT_MANAGE','PERM_API_KEY_CROSS_TENANT_MANAGE')")
    public ResponseEntity<ApiResponse<ApiKeyPageResponse>> listByTenant(
            @PathVariable Long tenantId,
            @AuthenticationPrincipal UserAccount currentUser,
            @RequestParam(required = false) String keyword,
            @RequestParam(required = false) String status,
            @RequestParam(required = false) Long userId,
            @RequestParam(required = false) Integer page,
            @RequestParam(required = false) Integer size) {
        ApiKeyQuery q = new ApiKeyQuery(keyword, status, userId, tenantId, page, size);
        return ResponseEntity.ok(ApiResponse.success(
                apiKeyService.listKeys(currentUser, ApiKeyService.Scope.TENANT, q)));
    }

    @GetMapping("/api/tenant/{tenantId}/api-keys/stats")
    @PreAuthorize("hasAnyAuthority('PERM_API_KEY_TENANT_MANAGE','PERM_API_KEY_CROSS_TENANT_MANAGE')")
    public ResponseEntity<ApiResponse<ApiKeyStats>> statsByTenant(
            @PathVariable Long tenantId,
            @AuthenticationPrincipal UserAccount currentUser) {
        // service 的 Scope=TENANT 会基于路径 tenantId 校验访问归属与计算过滤；
        // 这里直接委托即可
        return ResponseEntity.ok(ApiResponse.success(
                apiKeyService.getStats(currentUser, ApiKeyService.Scope.TENANT, tenantId)));
    }

    @PostMapping("/api/tenant/{tenantId}/api-keys")
    @PreAuthorize("hasAnyAuthority('PERM_API_KEY_TENANT_MANAGE','PERM_API_KEY_CROSS_TENANT_MANAGE')")
    public ResponseEntity<ApiResponse<CreatedApiKeyResponse>> createInTenant(
            @PathVariable Long tenantId,
            @RequestBody CreateApiKeyRequest req,
            @AuthenticationPrincipal UserAccount currentUser) {
        return ResponseEntity.ok(ApiResponse.success(
                apiKeyService.createKey(currentUser, tenantId, req)));
    }

    // ============================================================
    // 平台路径（仅平台管理员）
    // ============================================================

    @GetMapping("/api/admin/api-keys")
    @PreAuthorize("hasAuthority('PERM_API_KEY_CROSS_TENANT_MANAGE')")
    public ResponseEntity<ApiResponse<ApiKeyPageResponse>> listAll(
            @AuthenticationPrincipal UserAccount currentUser,
            @RequestParam(required = false) String keyword,
            @RequestParam(required = false) String status,
            @RequestParam(required = false) Long tenantId,
            @RequestParam(required = false) Long userId,
            @RequestParam(required = false) Integer page,
            @RequestParam(required = false) Integer size) {
        ApiKeyQuery q = new ApiKeyQuery(keyword, status, userId, tenantId, page, size);
        return ResponseEntity.ok(ApiResponse.success(
                apiKeyService.listKeys(currentUser, ApiKeyService.Scope.PLATFORM, q)));
    }

    @GetMapping("/api/admin/api-keys/stats")
    @PreAuthorize("hasAuthority('PERM_API_KEY_CROSS_TENANT_MANAGE')")
    public ResponseEntity<ApiResponse<ApiKeyStats>> statsAll(
            @AuthenticationPrincipal UserAccount currentUser) {
        return ResponseEntity.ok(ApiResponse.success(
                apiKeyService.getStats(currentUser, ApiKeyService.Scope.PLATFORM)));
    }
}
