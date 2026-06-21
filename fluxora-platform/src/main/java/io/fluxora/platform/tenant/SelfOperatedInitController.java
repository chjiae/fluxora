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

@RestController
@RequestMapping("/api/tenant/self-operated")
public class SelfOperatedInitController {

    private final TenantService tenantService;
    private final PasswordEncoder passwordEncoder;

    public SelfOperatedInitController(TenantService tenantService, PasswordEncoder passwordEncoder) {
        this.tenantService = tenantService;
        this.passwordEncoder = passwordEncoder;
    }

    @GetMapping("/status")
    @PreAuthorize("hasAuthority('PERM_PLATFORM_CONSOLE_ACCESS')")
    public ResponseEntity<ApiResponse<Map<String, Boolean>>> status() {
        boolean initialized = tenantService.isSelfOperatedInitialized();
        return ResponseEntity.ok(ApiResponse.success(Map.of("initialized", initialized)));
    }

    @PostMapping("/initialize")
    @PreAuthorize("hasAuthority('PERM_TENANT_CREATE')")
    public ResponseEntity<ApiResponse<TenantService.TenantInitResult>> initialize(
            @RequestBody SelfOperatedInitRequest request) {
        String encodedPassword = passwordEncoder.encode(request.adminPassword());
        TenantService.TenantInitResult result = tenantService.initializeSelfOperated(
                request.tenantName(), request.adminUsername(), encodedPassword, request.adminDisplayName());
        return ResponseEntity.ok(ApiResponse.success(result));
    }

    public record SelfOperatedInitRequest(String tenantName, String adminUsername,
                                          String adminPassword, String adminDisplayName) {}
}
