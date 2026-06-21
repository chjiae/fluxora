package io.fluxora.platform.tenant;

import io.fluxora.common.error.BusinessErrorCode;
import io.fluxora.common.error.ErrorResponse;
import io.fluxora.common.response.ApiResponse;
import io.fluxora.platform.identity.entity.UserAccount;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.Instant;
import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/tenant")
public class TenantController {

    private final TenantMapper tenantMapper;
    private final TenantService tenantService;

    public TenantController(TenantMapper tenantMapper, TenantService tenantService) {
        this.tenantMapper = tenantMapper;
        this.tenantService = tenantService;
    }

    private void requirePlatformAdmin() {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth == null || auth.getAuthorities().stream()
                .noneMatch(a -> "PERM_PLATFORM_ADMIN".equals(a.getAuthority()))) {
            throw new TenantException(BusinessErrorCode.ACCESS_DENIED);
        }
    }

    @GetMapping
    public ResponseEntity<ApiResponse<Map<String, Object>>> list(
            @RequestParam(defaultValue = "") String keyword,
            @RequestParam(defaultValue = "") String type,
            @RequestParam(required = false) Boolean enabled,
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "20") int size) {

        requirePlatformAdmin();

        int offset = (page - 1) * size;
        List<Tenant> tenants = tenantMapper.findAll(
                keyword.isBlank() ? null : keyword,
                type.isBlank() ? null : type,
                enabled, offset, size);
        long total = tenantMapper.countAll(
                keyword.isBlank() ? null : keyword,
                type.isBlank() ? null : type,
                enabled);

        return ResponseEntity.ok(ApiResponse.success(
                Map.of("items", (Object) tenants, "total", total, "page", page, "size", size)));
    }

    @GetMapping("/{id}")
    public ResponseEntity<ApiResponse<Tenant>> detail(@PathVariable Long id) {
        requirePlatformAdmin();
        Tenant tenant = tenantMapper.findById(id)
                .orElseThrow(() -> new TenantException(BusinessErrorCode.RESOURCE_NOT_FOUND, "租户不存在"));
        return ResponseEntity.ok(ApiResponse.success(tenant));
    }

    @PostMapping
    public ResponseEntity<ApiResponse<Tenant>> create(@RequestBody TenantCreateRequest request) {
        requirePlatformAdmin();
        if (tenantMapper.existsByCode(request.tenantCode())) {
            throw new TenantException(BusinessErrorCode.TENANT_CODE_DUPLICATE);
        }

        Tenant tenant = new Tenant();
        tenant.setTenantCode(request.tenantCode());
        tenant.setName(request.name());
        tenant.setType(request.type() != null ? request.type() : "THIRD_PARTY");
        tenant.setEnabled(request.enabled() != null ? request.enabled() : true);
        tenantMapper.insert(tenant);

        return ResponseEntity.ok(ApiResponse.success(tenant));
    }

    @PutMapping("/{id}")
    public ResponseEntity<ApiResponse<Tenant>> update(@PathVariable Long id,
                                                       @RequestBody TenantUpdateRequest request) {
        requirePlatformAdmin();
        Tenant tenant = tenantMapper.findById(id)
                .orElseThrow(() -> new TenantException(BusinessErrorCode.RESOURCE_NOT_FOUND, "租户不存在"));

        tenant.setName(request.name());
        tenant.setEnabled(request.enabled());
        if (request.expireAt() != null) {
            tenant.setExpireAt(Instant.parse(request.expireAt()));
        } else {
            tenant.setExpireAt(null);
        }
        tenantMapper.update(tenant);

        return ResponseEntity.ok(ApiResponse.success(tenant));
    }

    @PutMapping("/{id}/toggle")
    public ResponseEntity<ApiResponse<Tenant>> toggle(@PathVariable Long id,
                                                       @RequestBody Map<String, Boolean> body) {
        requirePlatformAdmin();
        tenantService.assertSelfOperatedProtected(id);
        Tenant tenant = tenantMapper.findById(id)
                .orElseThrow(() -> new TenantException(BusinessErrorCode.RESOURCE_NOT_FOUND, "租户不存在"));
        tenant.setEnabled(body.get("enabled"));
        tenantMapper.update(tenant);
        return ResponseEntity.ok(ApiResponse.success(tenant));
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<ApiResponse<Void>> delete(@PathVariable Long id) {
        requirePlatformAdmin();
        tenantService.assertSelfOperatedProtected(id);
        tenantMapper.softDelete(id);
        return ResponseEntity.ok(ApiResponse.success(null));
    }

    public record TenantCreateRequest(String tenantCode, String name, String type, Boolean enabled) {}
    public record TenantUpdateRequest(String name, Boolean enabled, String expireAt) {}
}
