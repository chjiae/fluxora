package io.fluxora.platform.runtime.availability;

import io.fluxora.common.response.ApiResponse;
import io.fluxora.platform.identity.entity.UserAccount;
import io.fluxora.platform.upstream.security.UpstreamTenantGuard;
import java.util.List;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 上游运行时故障状态管理接口。
 * 仅平台管理员和租户管理员可查看和恢复；普通成员无权限。
 * 恢复操作写入 outbox 触发快照重建，Gateway 在下一次 L1 刷新后即可调度已恢复资源。
 */
@RestController
@RequestMapping("/api/runtime-states")
public class UpstreamRuntimeStateController {

    private final UpstreamRuntimeFailureService failureService;
    private final UpstreamTenantGuard tenantGuard;

    public UpstreamRuntimeStateController(UpstreamRuntimeFailureService failureService,
                                           UpstreamTenantGuard tenantGuard) {
        this.failureService = failureService;
        this.tenantGuard = tenantGuard;
    }

    /** 列出所有非 AVAILABLE 的运行时状态。平台管理员可查全部租户；租户管理员仅见本租户。 */
    @GetMapping
    @PreAuthorize("hasAuthority('PERM_UPSTREAM_READ')")
    public ResponseEntity<ApiResponse<List<RuntimeStateRow>>> list(
            @AuthenticationPrincipal UserAccount user, Authentication auth) {
        boolean platform = tenantGuard.isPlatformAdmin(auth);
        Long tenantId = platform ? null : user.getTenantId();
        return ResponseEntity.ok(ApiResponse.success(failureService.listNonAvailableStates(tenantId)));
    }

    /** 手动恢复指定资源的运行时状态为 AVAILABLE，并触发快照重建。 */
    @PostMapping("/{scopeType}/{scopeKey}/recover")
    @PreAuthorize("hasAuthority('PERM_UPSTREAM_CREATE')")
    public ResponseEntity<ApiResponse<Void>> recover(
            @PathVariable String scopeType,
            @PathVariable String scopeKey) {
        failureService.recoverState(scopeType, scopeKey);
        return ResponseEntity.ok(ApiResponse.success(null));
    }
}
