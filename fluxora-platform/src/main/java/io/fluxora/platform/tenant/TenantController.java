package io.fluxora.platform.tenant;

import io.fluxora.common.error.BusinessErrorCode;
import io.fluxora.common.response.ApiResponse;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.DeleteMapping;
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

    /**
     * 分页查询租户列表。
     * 支持：租户码/名称模糊搜索、类型筛选（SELF_OPERATED/STANDARD）、
     * 业务状态筛选（ENABLED/DISABLED/EXPIRED）、过期时间范围筛选。
     * 需要权限：PERM_TENANT_READ
     */
    @GetMapping
    @PreAuthorize("hasAuthority('PERM_TENANT_READ')")
    public ResponseEntity<ApiResponse<Map<String, Object>>> list(
            @RequestParam(defaultValue = "") String keyword,
            @RequestParam(defaultValue = "") String type,
            @RequestParam(defaultValue = "") String status,
            @RequestParam(required = false) String expireFrom,
            @RequestParam(required = false) String expireTo,
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "20") int size) {

        int offset = (page - 1) * size;
        Instant from = expireFrom != null && !expireFrom.isBlank() ? Instant.parse(expireFrom + "T00:00:00Z") : null;
        Instant to = expireTo != null && !expireTo.isBlank() ? Instant.parse(expireTo + "T23:59:59Z") : null;

        List<Tenant> tenants = tenantMapper.findAll(
                keyword.isBlank() ? null : keyword,
                type.isBlank() ? null : type,
                status.isBlank() ? null : status,
                from, to, offset, size);
        long total = tenantMapper.countAll(
                keyword.isBlank() ? null : keyword,
                type.isBlank() ? null : type,
                status.isBlank() ? null : status,
                from, to);

        return ResponseEntity.ok(ApiResponse.success(
                Map.of("items", (Object) tenants, "total", total, "page", page, "size", size)));
    }

    @GetMapping("/{id}")
    @PreAuthorize("hasAuthority('PERM_TENANT_READ')")
    public ResponseEntity<ApiResponse<Tenant>> detail(@PathVariable Long id) {
        Tenant tenant = tenantMapper.findById(id)
                .orElseThrow(() -> new TenantException(BusinessErrorCode.RESOURCE_NOT_FOUND, "租户不存在"));
        return ResponseEntity.ok(ApiResponse.success(tenant));
    }

    @PostMapping
    @PreAuthorize("hasAuthority('PERM_TENANT_CREATE')")
    public ResponseEntity<ApiResponse<Tenant>> create(@RequestBody TenantCreateRequest request) {
        if (tenantMapper.existsByCode(request.tenantCode())) {
            throw new TenantException(BusinessErrorCode.TENANT_CODE_DUPLICATE);
        }

        String type = request.type() != null ? request.type() : TenantService.STANDARD;
        tenantService.assertNotSelfOperatedType(type);

        Tenant tenant = new Tenant();
        tenant.setTenantCode(request.tenantCode());
        tenant.setName(request.name());
        tenant.setDescription(request.description());
        tenant.setType(type);
        tenant.setEnabled(request.enabled() != null ? request.enabled() : true);
        tenantMapper.insert(tenant);

        return ResponseEntity.ok(ApiResponse.success(tenant));
    }

    /**
     * 编辑租户基础信息。
     * 自营租户仅允许修改 name 和 description，不允许修改 enabled、expireAt。
     * 需要权限：PERM_TENANT_UPDATE
     */
    @PutMapping("/{id}")
    @PreAuthorize("hasAuthority('PERM_TENANT_UPDATE')")
    public ResponseEntity<ApiResponse<Tenant>> update(@PathVariable Long id,
                                                       @RequestBody TenantUpdateRequest request) {
        Tenant tenant = tenantMapper.findById(id)
                .orElseThrow(() -> new TenantException(BusinessErrorCode.RESOURCE_NOT_FOUND, "租户不存在"));

        // 自营租户仅允许修改 name、description
        tenant.setName(request.name());
        if (request.description() != null) {
            tenant.setDescription(request.description());
        }
        // 非自营租户允许修改 enabled、expireAt
        if (!TenantService.SELF_OPERATED.equals(tenant.getType())) {
            if (request.enabled() != null) {
                tenant.setEnabled(request.enabled());
            }
            if (request.expireAt() != null) {
                tenant.setExpireAt(Instant.parse(request.expireAt()));
            } else if (request.clearExpireAt() != null && request.clearExpireAt()) {
                tenant.setExpireAt(null);
            }
        }

        tenantMapper.update(tenant);
        return ResponseEntity.ok(ApiResponse.success(tenant));
    }

    @PutMapping("/{id}/enable")
    @PreAuthorize("hasAuthority('PERM_TENANT_ENABLE')")
    public ResponseEntity<ApiResponse<Tenant>> enable(@PathVariable Long id) {
        tenantService.assertSelfOperatedProtected(id);
        tenantMapper.enableTenant(id);
        Tenant tenant = tenantMapper.findById(id)
                .orElseThrow(() -> new TenantException(BusinessErrorCode.RESOURCE_NOT_FOUND, "租户不存在"));
        return ResponseEntity.ok(ApiResponse.success(tenant));
    }

    /**
     * 停用租户。自营租户受保护，禁止停用。
     * 停用后该租户下所有用户将无法登录和访问受保护接口。
     * 需要权限：PERM_TENANT_DISABLE
     */
    @PutMapping("/{id}/disable")
    @PreAuthorize("hasAuthority('PERM_TENANT_DISABLE')")
    public ResponseEntity<ApiResponse<Tenant>> disable(@PathVariable Long id) {
        tenantService.assertSelfOperatedProtected(id);
        tenantMapper.disableTenant(id);
        Tenant tenant = tenantMapper.findById(id)
                .orElseThrow(() -> new TenantException(BusinessErrorCode.RESOURCE_NOT_FOUND, "租户不存在"));
        return ResponseEntity.ok(ApiResponse.success(tenant));
    }

    @PutMapping("/{id}/expire")
    @PreAuthorize("hasAuthority('PERM_TENANT_EXPIRE_SET')")
    public ResponseEntity<ApiResponse<Tenant>> setExpire(@PathVariable Long id,
                                                          @RequestBody Map<String, String> body) {
        tenantService.assertSelfOperatedProtected(id);
        Instant expireAt = null;
        if (body.containsKey("expireAt") && body.get("expireAt") != null && !body.get("expireAt").isBlank()) {
            expireAt = Instant.parse(body.get("expireAt"));
        }
        tenantMapper.setExpireAt(id, expireAt);
        Tenant tenant = tenantMapper.findById(id)
                .orElseThrow(() -> new TenantException(BusinessErrorCode.RESOURCE_NOT_FOUND, "租户不存在"));
        return ResponseEntity.ok(ApiResponse.success(tenant));
    }

    @DeleteMapping("/{id}")
    @PreAuthorize("hasAuthority('PERM_TENANT_DELETE')")
    public ResponseEntity<ApiResponse<Void>> delete(@PathVariable Long id) {
        tenantService.assertSelfOperatedProtected(id);
        tenantMapper.softDelete(id);
        return ResponseEntity.ok(ApiResponse.success(null));
    }

    public record TenantCreateRequest(String tenantCode, String name, String description,
                                       String type, Boolean enabled) {}
    public record TenantUpdateRequest(String name, String description, Boolean enabled,
                                       String expireAt, Boolean clearExpireAt) {}
}
