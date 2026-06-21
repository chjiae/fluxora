package io.fluxora.platform.tenant;

import io.fluxora.common.response.ApiResponse;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

/**
 * 自营租户初始化控制器。
 * 仅平台管理员（拥有 PERM_TENANT_CREATE 权限）可执行初始化。
 * 初始化幂等：已存在 default 租户时重复调用返回业务错误。
 */
@RestController
@RequestMapping("/api/tenant/self-operated")
public class SelfOperatedInitController {

    private final TenantService tenantService;
    private final PasswordEncoder passwordEncoder;

    public SelfOperatedInitController(TenantService tenantService, PasswordEncoder passwordEncoder) {
        this.tenantService = tenantService;
        this.passwordEncoder = passwordEncoder;
    }

    /**
     * 查询自营租户是否已初始化。
     * 仅平台管理员（PERM_PLATFORM_ADMIN）可访问，租户管理员不可见。
     */
    @GetMapping("/status")
    @PreAuthorize("hasAuthority('PERM_PLATFORM_ADMIN')")
    public ResponseEntity<ApiResponse<Map<String, Boolean>>> status() {
        boolean initialized = tenantService.isSelfOperatedInitialized();
        return ResponseEntity.ok(ApiResponse.success(Map.of("initialized", initialized)));
    }

    /**
     * 初始化自营租户。
     * 仅平台管理员（PERM_PLATFORM_ADMIN）可执行，租户管理员返回 403。
     */
    @PostMapping("/initialize")
    @PreAuthorize("hasAuthority('PERM_PLATFORM_ADMIN')")
    public ResponseEntity<ApiResponse<TenantService.TenantInitResult>> initialize(
            @RequestBody SelfOperatedInitRequest request) {
        String encodedPassword = passwordEncoder.encode(request.adminPassword());
        TenantService.TenantInitResult result = tenantService.initializeSelfOperated(
                request.tenantName(), request.adminUsername(), encodedPassword, request.adminDisplayName());
        return ResponseEntity.ok(ApiResponse.success(result));
    }

    /** 自营初始化请求 */
    public record SelfOperatedInitRequest(String tenantName, String adminUsername,
                                          String adminPassword, String adminDisplayName) {}
}
