package io.fluxora.platform.model;

import io.fluxora.common.response.ApiResponse;
import io.fluxora.platform.identity.entity.UserAccount;
import io.fluxora.platform.model.dto.RouteTargetSummary;
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
import org.springframework.web.bind.annotation.RestController;

/**
 * 路由级接口：编辑路由、管理路由目标。
 * 路由的列表与创建挂在 /api/tenant-models/{tenantModelId}/routes 下；
 * 单条路由的编辑与目标 CRUD 走 /api/routes/{routeId} 路径，便于前端直接定位路由资源。
 */
@RestController
@RequestMapping("/api/routes")
public class ModelRouteController {

    private final ModelRouteService routeService;
    private final RouteTargetService targetService;

    public ModelRouteController(ModelRouteService routeService, RouteTargetService targetService) {
        this.routeService = routeService;
        this.targetService = targetService;
    }

    @PutMapping("/{routeId}")
    @PreAuthorize("hasAuthority('PERM_TENANT_MODEL_MANAGE')")
    public ResponseEntity<ApiResponse<Void>> updateRoute(
            @PathVariable Long routeId,
            @RequestBody RouteUpdateRequest req,
            @AuthenticationPrincipal UserAccount user, Authentication auth) {
        if (req.enabled() != null) routeService.setEnabled(routeId, req.enabled(), user, auth);
        if (req.remark() != null) routeService.updateRemark(routeId, req.remark(), user, auth);
        return ResponseEntity.ok(ApiResponse.success(null));
    }

    @DeleteMapping("/{routeId}")
    @PreAuthorize("hasAuthority('PERM_TENANT_MODEL_MANAGE')")
    public ResponseEntity<ApiResponse<Void>> deleteRoute(
            @PathVariable Long routeId,
            @AuthenticationPrincipal UserAccount user, Authentication auth) {
        routeService.delete(routeId, user, auth);
        return ResponseEntity.ok(ApiResponse.success(null));
    }

    // ========== 路由目标 ==========

    @GetMapping("/{routeId}/targets")
    @PreAuthorize("hasAuthority('PERM_TENANT_MODEL_READ')")
    public ResponseEntity<ApiResponse<List<RouteTargetSummary>>> listTargets(
            @PathVariable Long routeId,
            @AuthenticationPrincipal UserAccount user, Authentication auth) {
        return ResponseEntity.ok(ApiResponse.success(
                targetService.listTargets(routeId, user, auth)));
    }

    @PostMapping("/{routeId}/targets")
    @PreAuthorize("hasAuthority('PERM_TENANT_MODEL_MANAGE')")
    public ResponseEntity<ApiResponse<RouteTargetSummary>> createTarget(
            @PathVariable Long routeId,
            @RequestBody TargetCreateRequest req,
            @AuthenticationPrincipal UserAccount user, Authentication auth) {
        return ResponseEntity.ok(ApiResponse.success(targetService.create(
                routeId, req.tenantModelCandidateMappingId(), req.priority(), req.weight(),
                req.remark(), user, auth)));
    }

    @PutMapping("/{routeId}/targets/{targetId}")
    @PreAuthorize("hasAuthority('PERM_TENANT_MODEL_MANAGE')")
    public ResponseEntity<ApiResponse<RouteTargetSummary>> updateTarget(
            @PathVariable Long routeId,
            @PathVariable Long targetId,
            @RequestBody TargetUpdateRequest req,
            @AuthenticationPrincipal UserAccount user, Authentication auth) {
        return ResponseEntity.ok(ApiResponse.success(targetService.update(
                routeId, targetId, req.enabled(), req.priority(), req.weight(), req.remark(), user, auth)));
    }

    @DeleteMapping("/{routeId}/targets/{targetId}")
    @PreAuthorize("hasAuthority('PERM_TENANT_MODEL_MANAGE')")
    public ResponseEntity<ApiResponse<Void>> deleteTarget(
            @PathVariable Long routeId,
            @PathVariable Long targetId,
            @AuthenticationPrincipal UserAccount user, Authentication auth) {
        targetService.delete(routeId, targetId, user, auth);
        return ResponseEntity.ok(ApiResponse.success(null));
    }

    public record RouteUpdateRequest(Boolean enabled, String remark) {
    }

    public record TargetCreateRequest(Long tenantModelCandidateMappingId, Integer priority,
                                       Integer weight, String remark) {
    }

    public record TargetUpdateRequest(Boolean enabled, Integer priority, Integer weight, String remark) {
    }
}
