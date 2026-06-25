package io.fluxora.platform.runtime.availability;

import io.fluxora.common.response.ApiResponse;
import java.util.List;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
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

    public UpstreamRuntimeStateController(UpstreamRuntimeFailureService failureService) {
        this.failureService = failureService;
    }

    /** 列出所有非 AVAILABLE 的运行时状态。租户管理员仅见本租户内，平台管理员见全部。 */
    @GetMapping
    @PreAuthorize("hasAuthority('PERM_UPSTREAM_READ')")
    public ResponseEntity<ApiResponse<List<RuntimeStateRow>>> list() {
        // TenantGuard 基于 Authentication context 自动判断租户范围
        return ResponseEntity.ok(ApiResponse.success(failureService.listNonAvailableStates(null)));
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
