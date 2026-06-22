package io.fluxora.platform.identity;

import io.fluxora.common.response.ApiResponse;
import io.fluxora.platform.identity.dto.CreateMemberRequest;
import io.fluxora.platform.identity.dto.MemberPageResponse;
import io.fluxora.platform.identity.dto.MemberQuery;
import io.fluxora.platform.identity.dto.MemberSummary;
import io.fluxora.platform.identity.dto.ResetPasswordRequest;
import io.fluxora.platform.identity.dto.RoleOption;
import io.fluxora.platform.identity.dto.UpdateProfileRequest;
import io.fluxora.platform.identity.dto.UpdateRoleRequest;
import io.fluxora.platform.identity.entity.UserAccount;
import java.util.List;
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
 * 成员管理 REST 控制器。
 *
 * 路由分两类：
 *   - /api/tenant/{tenantId}/members*：平台管理员显式选择目标租户，传入路径参数；
 *     租户管理员若访问异租户路径会被服务层（resolveOperableTenantId）拒绝。
 *   - /api/members*：上下文租户由 currentUser.tenantId 自动决定，租户管理员日常入口。
 *
 * 所有业务规则与跨租户校验均由 {@link MemberService} 承载，Controller 仅做参数解构。
 * @PreAuthorize 仅做粗粒度权限网关，细粒度差异（PLATFORM_ADMIN vs TENANT_ADMIN）在服务层强制。
 */
@RestController
public class MemberController {

    private final MemberService memberService;

    public MemberController(MemberService memberService) {
        this.memberService = memberService;
    }

    // ============================================================
    // 列表：分别提供"按租户"和"按当前上下文"两个入口
    // ============================================================

    @GetMapping("/api/tenant/{tenantId}/members")
    @PreAuthorize("hasAuthority('PERM_MEMBER_READ')")
    public ResponseEntity<ApiResponse<MemberPageResponse>> listByTenant(
            @PathVariable Long tenantId,
            @AuthenticationPrincipal UserAccount currentUser,
            @RequestParam(required = false) String keyword,
            @RequestParam(required = false) String status,
            @RequestParam(required = false) String roleCode,
            @RequestParam(required = false) Integer page,
            @RequestParam(required = false) Integer size) {
        MemberQuery q = new MemberQuery(keyword, status, roleCode, page, size);
        return ResponseEntity.ok(ApiResponse.success(memberService.listMembers(currentUser, tenantId, q)));
    }

    @GetMapping("/api/members")
    @PreAuthorize("hasAuthority('PERM_MEMBER_READ')")
    public ResponseEntity<ApiResponse<MemberPageResponse>> listCurrentTenant(
            @AuthenticationPrincipal UserAccount currentUser,
            @RequestParam(required = false) String keyword,
            @RequestParam(required = false) String status,
            @RequestParam(required = false) String roleCode,
            @RequestParam(required = false) Integer page,
            @RequestParam(required = false) Integer size) {
        MemberQuery q = new MemberQuery(keyword, status, roleCode, page, size);
        // 传入 null 触发服务层使用 currentUser.tenantId（仅租户管理员路径）
        return ResponseEntity.ok(ApiResponse.success(memberService.listMembers(currentUser, null, q)));
    }

    // ============================================================
    // 详情
    // ============================================================

    @GetMapping("/api/members/{id}")
    @PreAuthorize("hasAuthority('PERM_MEMBER_READ')")
    public ResponseEntity<ApiResponse<MemberSummary>> detail(
            @PathVariable Long id,
            @AuthenticationPrincipal UserAccount currentUser) {
        return ResponseEntity.ok(ApiResponse.success(memberService.getMember(currentUser, id)));
    }

    // ============================================================
    // 创建：路径携带 tenantId，平台管理员、租户管理员同入口
    //   - 平台管理员可指定任意租户；
    //   - 租户管理员传入异租户将由服务层拒绝。
    // ============================================================

    @PostMapping("/api/tenant/{tenantId}/members")
    @PreAuthorize("hasAuthority('PERM_MEMBER_CREATE')")
    public ResponseEntity<ApiResponse<MemberSummary>> create(
            @PathVariable Long tenantId,
            @RequestBody CreateMemberRequest request,
            @AuthenticationPrincipal UserAccount currentUser) {
        return ResponseEntity.ok(ApiResponse.success(
                memberService.createMember(currentUser, tenantId, request)));
    }

    // ============================================================
    // 编辑、角色、启停、删除、改密
    // ============================================================

    @PutMapping("/api/members/{id}")
    @PreAuthorize("hasAuthority('PERM_MEMBER_UPDATE')")
    public ResponseEntity<ApiResponse<MemberSummary>> updateProfile(
            @PathVariable Long id,
            @RequestBody UpdateProfileRequest request,
            @AuthenticationPrincipal UserAccount currentUser) {
        return ResponseEntity.ok(ApiResponse.success(
                memberService.updateProfile(currentUser, id, request)));
    }

    @PutMapping("/api/members/{id}/role")
    @PreAuthorize("hasAuthority('PERM_MEMBER_UPDATE')")
    public ResponseEntity<ApiResponse<MemberSummary>> updateRole(
            @PathVariable Long id,
            @RequestBody UpdateRoleRequest request,
            @AuthenticationPrincipal UserAccount currentUser) {
        return ResponseEntity.ok(ApiResponse.success(
                memberService.updateRole(currentUser, id, request.roleCode())));
    }

    @PutMapping("/api/members/{id}/enable")
    @PreAuthorize("hasAuthority('PERM_MEMBER_ENABLE')")
    public ResponseEntity<ApiResponse<MemberSummary>> enable(
            @PathVariable Long id,
            @AuthenticationPrincipal UserAccount currentUser) {
        return ResponseEntity.ok(ApiResponse.success(memberService.enableMember(currentUser, id)));
    }

    @PutMapping("/api/members/{id}/disable")
    @PreAuthorize("hasAuthority('PERM_MEMBER_DISABLE')")
    public ResponseEntity<ApiResponse<MemberSummary>> disable(
            @PathVariable Long id,
            @AuthenticationPrincipal UserAccount currentUser) {
        return ResponseEntity.ok(ApiResponse.success(memberService.disableMember(currentUser, id)));
    }

    @DeleteMapping("/api/members/{id}")
    @PreAuthorize("hasAuthority('PERM_MEMBER_DELETE')")
    public ResponseEntity<ApiResponse<Void>> delete(
            @PathVariable Long id,
            @AuthenticationPrincipal UserAccount currentUser) {
        memberService.deleteMember(currentUser, id);
        return ResponseEntity.ok(ApiResponse.success(null));
    }

    @PutMapping("/api/members/{id}/password")
    @PreAuthorize("hasAuthority('PERM_MEMBER_PASSWORD_RESET')")
    public ResponseEntity<ApiResponse<Void>> resetPassword(
            @PathVariable Long id,
            @RequestBody ResetPasswordRequest request,
            @AuthenticationPrincipal UserAccount currentUser) {
        memberService.resetPassword(currentUser, id, request.newPassword());
        return ResponseEntity.ok(ApiResponse.success(null));
    }

    // ============================================================
    // 可分配角色
    // ============================================================

    @GetMapping("/api/members/assignable-roles")
    @PreAuthorize("hasAuthority('PERM_MEMBER_READ')")
    public ResponseEntity<ApiResponse<List<RoleOption>>> assignableRoles(
            @AuthenticationPrincipal UserAccount currentUser) {
        return ResponseEntity.ok(ApiResponse.success(memberService.listAssignableRoles(currentUser)));
    }

    // ============================================================
    // 聚合统计：指标条用，分别给平台 / 租户管理员两个入口
    // ============================================================

    @GetMapping("/api/tenant/{tenantId}/members/stats")
    @PreAuthorize("hasAuthority('PERM_MEMBER_READ')")
    public ResponseEntity<ApiResponse<io.fluxora.platform.identity.mapper.MemberStats>> statsByTenant(
            @PathVariable Long tenantId,
            @AuthenticationPrincipal UserAccount currentUser) {
        return ResponseEntity.ok(ApiResponse.success(memberService.getStats(currentUser, tenantId)));
    }

    @GetMapping("/api/members/stats")
    @PreAuthorize("hasAuthority('PERM_MEMBER_READ')")
    public ResponseEntity<ApiResponse<io.fluxora.platform.identity.mapper.MemberStats>> statsCurrentTenant(
            @AuthenticationPrincipal UserAccount currentUser) {
        return ResponseEntity.ok(ApiResponse.success(memberService.getStats(currentUser, null)));
    }
}
