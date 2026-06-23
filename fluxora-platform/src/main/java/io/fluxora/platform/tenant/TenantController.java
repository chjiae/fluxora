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

/**
 * 租户管理控制器。
 * 所有端点通过 @PreAuthorize 进行细粒度权限校验，
 * 自营租户保护在服务层统一执行。
 */
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
    public ResponseEntity<ApiResponse<TenantPageResponse>> list(
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

        var tenants = tenantMapper.findAll(
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
                new TenantPageResponse(tenants, total, page, size)));
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
        String type = request.type() != null ? request.type() : TenantService.STANDARD;

        Tenant tenant = new Tenant();
        tenant.setTenantCode(request.tenantCode());
        tenant.setName(request.name());
        tenant.setDescription(request.description());
        tenant.setType(type);
        tenant.setEnabled(request.enabled() != null ? request.enabled() : true);
        return ResponseEntity.ok(ApiResponse.success(tenantService.createTenant(tenant)));
    }

    /**
     * 编辑租户基础信息（仅 name、description）。
     * 启用/停用请使用 /enable、/disable 专用接口，
     * 设置过期时间请使用 /expire 专用接口。
     * 自营租户同样仅允许修改 name、description。
     * 需要权限：PERM_TENANT_UPDATE
     */
    @PutMapping("/{id}")
    @PreAuthorize("hasAuthority('PERM_TENANT_UPDATE')")
    public ResponseEntity<ApiResponse<Tenant>> update(@PathVariable Long id,
                                                       @RequestBody TenantUpdateRequest request) {
        return ResponseEntity.ok(ApiResponse.success(
                tenantService.updateTenantBasic(id, request.name(), request.description())));
    }

    @PutMapping("/{id}/enable")
    @PreAuthorize("hasAuthority('PERM_TENANT_ENABLE')")
    public ResponseEntity<ApiResponse<Tenant>> enable(@PathVariable Long id) {
        return ResponseEntity.ok(ApiResponse.success(tenantService.enableTenant(id)));
    }

    /**
     * 停用租户。自营租户受保护，禁止停用。
     * 停用后该租户下所有用户将无法登录和访问受保护接口。
     * 需要权限：PERM_TENANT_DISABLE
     */
    @PutMapping("/{id}/disable")
    @PreAuthorize("hasAuthority('PERM_TENANT_DISABLE')")
    public ResponseEntity<ApiResponse<Tenant>> disable(@PathVariable Long id) {
        return ResponseEntity.ok(ApiResponse.success(tenantService.disableTenant(id)));
    }

    @PutMapping("/{id}/expire")
    @PreAuthorize("hasAuthority('PERM_TENANT_EXPIRE_SET')")
    public ResponseEntity<ApiResponse<Tenant>> setExpire(@PathVariable Long id,
                                                          @RequestBody SetTenantExpireRequest request) {
        Instant expireAt = null;
        if (request.expireAt() != null && !request.expireAt().isBlank()) {
            expireAt = Instant.parse(request.expireAt());
        }
        return ResponseEntity.ok(ApiResponse.success(tenantService.setTenantExpireAt(id, expireAt)));
    }

    @DeleteMapping("/{id}")
    @PreAuthorize("hasAuthority('PERM_TENANT_DELETE')")
    public ResponseEntity<ApiResponse<Void>> delete(@PathVariable Long id) {
        tenantService.deleteTenant(id);
        return ResponseEntity.ok(ApiResponse.success(null));
    }

    /**
     * 租户聚合统计：单次 SQL 返回 6 个计数，专供「概览」与「租户管理」顶部指标条。
     * 不暴露任何敏感字段；过期阈值默认 30 天，前端可通过参数调整。
     * 需要权限：PERM_TENANT_READ
     */
    @GetMapping("/stats")
    @PreAuthorize("hasAuthority('PERM_TENANT_READ')")
    public ResponseEntity<ApiResponse<TenantStats>> stats(
            @RequestParam(name = "expiringWithinDays", defaultValue = "30") int expiringWithinDays) {
        // 上限保护，避免传入过大窗口对查询计划产生影响
        int days = Math.max(1, Math.min(expiringWithinDays, 365));
        return ResponseEntity.ok(ApiResponse.success(tenantMapper.stats(days)));
    }

    public record TenantCreateRequest(String tenantCode, String name, String description,
                                       String type, Boolean enabled) {}
    /** 编辑租户请求：仅 name、description，启用/停用/过期使用专用接口 */
    public record TenantUpdateRequest(String name, String description) {}
}
