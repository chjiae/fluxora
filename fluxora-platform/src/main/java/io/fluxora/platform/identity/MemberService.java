package io.fluxora.platform.identity;

import io.fluxora.common.error.BusinessErrorCode;
import io.fluxora.platform.identity.dto.CreateMemberRequest;
import io.fluxora.platform.identity.dto.MemberPageResponse;
import io.fluxora.platform.identity.dto.MemberQuery;
import io.fluxora.platform.identity.dto.MemberSummary;
import io.fluxora.platform.identity.dto.RoleOption;
import io.fluxora.platform.identity.dto.UpdateProfileRequest;
import io.fluxora.platform.identity.entity.Role;
import io.fluxora.platform.identity.entity.UserAccount;
import io.fluxora.platform.identity.mapper.IdentityMapper;
import io.fluxora.platform.identity.mapper.MemberRow;
import io.fluxora.platform.tenant.Tenant;
import io.fluxora.platform.tenant.TenantException;
import io.fluxora.platform.tenant.TenantMapper;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 成员管理服务。
 *
 * 本服务承载所有成员相关业务规则，Controller 仅做参数解构与权限路由（@PreAuthorize）：
 *
 *   1. 跨租户保护：{@link #resolveOperableTenantId} 强制 TENANT_ADMIN 只能操作自身租户，
 *      忽略客户端传入的 tenantId；PLATFORM_ADMIN 必须显式指定 tenantId。
 *   2. 角色可分配性：{@link #assertRoleAssignable} 阻止任何人创建 PLATFORM_ADMIN，
 *      并阻止 TENANT_ADMIN 把成员升级/降级为 TENANT_ADMIN。
 *   3. 最后管理员保护：{@link #assertNotLastTenantAdmin} 在停用/删除/降级 TENANT_ADMIN
 *      之前预检，保证未删除租户始终有至少一名启用的管理员。
 *   4. 密码处理：本服务内部完成强度校验（{@link #assertPasswordStrong}）与 BCrypt 加密，
 *      不依赖调用方预加密，避免 TenantService.initializeSelfOperated 的历史
 *      "Controller 加密、Service 直存" 错位。
 *
 * 所有写操作均带 @Transactional 边界，确保「用户表写入 + 角色关联」原子提交。
 */
@Service
public class MemberService {

    private static final Logger log = LoggerFactory.getLogger(MemberService.class);

    /** 本轮可被分配的租户级角色集合，PLATFORM_ADMIN 不在其中以严格隔离作用域 */
    private static final Set<String> TENANT_SCOPE_ROLES = Set.of("TENANT_ADMIN", "TENANT_MEMBER");
    private static final String PLATFORM_ADMIN_ROLE = "PLATFORM_ADMIN";
    private static final String TENANT_ADMIN_ROLE = "TENANT_ADMIN";
    private static final String TENANT_MEMBER_ROLE = "TENANT_MEMBER";

    private final IdentityMapper identityMapper;
    private final TenantMapper tenantMapper;
    private final PasswordEncoder passwordEncoder;

    public MemberService(IdentityMapper identityMapper,
                         TenantMapper tenantMapper,
                         PasswordEncoder passwordEncoder) {
        this.identityMapper = identityMapper;
        this.tenantMapper = tenantMapper;
        this.passwordEncoder = passwordEncoder;
    }

    // ============================================================
    // 列表 & 详情
    // ============================================================

    /**
     * 分页查询成员。
     *
     * @param currentUser    当前登录用户
     * @param requestedTenantId 平台管理员传入的目标租户 ID；租户管理员忽略该参数
     * @param query          关键字 / 状态 / 角色过滤
     */
    @Transactional(readOnly = true)
    public MemberPageResponse listMembers(UserAccount currentUser,
                                          Long requestedTenantId,
                                          MemberQuery query) {
        Long tenantId = resolveOperableTenantId(currentUser, requestedTenantId);
        // 平台管理员查看任意租户（含已停用/过期）成员视为只读允许，无需 assertTenantValidOrThrow。
        assertTenantExists(tenantId);

        int page = query.pageOrDefault();
        int size = query.sizeOrDefault();
        int offset = (page - 1) * size;

        List<MemberRow> rows = identityMapper.findMembers(
                tenantId, blankToNull(query.keyword()), blankToNull(query.status()),
                blankToNull(query.roleCode()), offset, size);
        long total = identityMapper.countMembers(
                tenantId, blankToNull(query.keyword()), blankToNull(query.status()),
                blankToNull(query.roleCode()));

        List<MemberSummary> items = rows.stream().map(this::toSummary).toList();
        return new MemberPageResponse(items, total, page, size);
    }

    /** 查询成员详情；服务层校验当前操作者是否有权访问该成员所属租户 */
    @Transactional(readOnly = true)
    public MemberSummary getMember(UserAccount currentUser, Long memberId) {
        MemberRow row = identityMapper.findMemberDetail(memberId)
                .orElseThrow(() -> new MemberException(BusinessErrorCode.MEMBER_NOT_FOUND));
        assertSameTenant(currentUser, row.getTenantId());
        return toSummary(row);
    }

    // ============================================================
    // 创建
    // ============================================================

    /**
     * 创建成员。
     *
     * 流程：
     *   1. 解析目标租户（跨租户保护）；
     *   2. 校验目标角色可分配性；
     *   3. 校验用户名未被未删除记录占用；
     *   4. 校验密码强度；
     *   5. BCrypt 加密后插入 user_account，并写入 user_role 关联。
     */
    @Transactional
    public MemberSummary createMember(UserAccount currentUser,
                                      Long requestedTenantId,
                                      CreateMemberRequest req) {
        Long tenantId = resolveOperableTenantId(currentUser, requestedTenantId);
        assertTenantWritable(tenantId);

        if (req == null || isBlank(req.username()) || isBlank(req.password())
                || isBlank(req.roleCode())) {
            throw new MemberException(BusinessErrorCode.VALIDATION_ERROR);
        }
        assertRoleAssignable(currentUser, req.roleCode());
        assertPasswordStrong(req.password());

        if (identityMapper.existsByUsername(req.username())) {
            throw new MemberException(BusinessErrorCode.USERNAME_DUPLICATE);
        }

        Role role = identityMapper.findRoleByCode(req.roleCode())
                .orElseThrow(() -> new MemberException(BusinessErrorCode.ROLE_NOT_ASSIGNABLE));
        if (!"TENANT".equals(role.getScopeType())) {
            // 不允许把平台角色分配给租户成员
            throw new MemberException(BusinessErrorCode.ROLE_NOT_ASSIGNABLE);
        }

        UserAccount user = new UserAccount();
        user.setUsername(req.username().trim());
        user.setDisplayName(blankToNull(req.displayName()));
        user.setEmail(blankToNull(req.email()));
        user.setScopeType("TENANT");
        user.setTenantId(tenantId);
        user.setEnabled(true);
        // BCrypt 在服务层加密，确保所有创建路径一致；不接受 Controller 预加密
        user.setPasswordHash(passwordEncoder.encode(req.password()));
        identityMapper.insertUser(user);
        identityMapper.insertUserRole(user.getId(), role.getId());

        log.info("成员已创建：tenantId={}, userId={}, role={}", tenantId, user.getId(), role.getCode());
        return loadSummary(user.getId());
    }

    // ============================================================
    // 编辑
    // ============================================================

    @Transactional
    public MemberSummary updateProfile(UserAccount currentUser, Long memberId,
                                       UpdateProfileRequest req) {
        MemberRow row = loadRowOrThrow(memberId);
        assertSameTenant(currentUser, row.getTenantId());
        assertTenantWritable(row.getTenantId());
        identityMapper.updateProfile(memberId, blankToNull(req.displayName()),
                blankToNull(req.email()));
        return loadSummary(memberId);
    }

    /**
     * 调整成员角色。
     *
     * 触发最后管理员保护：当目标当前是 TENANT_ADMIN 且新角色不再是 TENANT_ADMIN 时，
     * 必须保证该租户内剩余至少一名启用的 TENANT_ADMIN。
     */
    @Transactional
    public MemberSummary updateRole(UserAccount currentUser, Long memberId, String newRoleCode) {
        if (isBlank(newRoleCode)) {
            throw new MemberException(BusinessErrorCode.VALIDATION_ERROR);
        }
        MemberRow row = loadRowOrThrow(memberId);
        assertSameTenant(currentUser, row.getTenantId());
        assertTenantWritable(row.getTenantId());
        assertRoleAssignable(currentUser, newRoleCode);

        Role newRole = identityMapper.findRoleByCode(newRoleCode)
                .orElseThrow(() -> new MemberException(BusinessErrorCode.ROLE_NOT_ASSIGNABLE));
        if (!"TENANT".equals(newRole.getScopeType())) {
            throw new MemberException(BusinessErrorCode.ROLE_NOT_ASSIGNABLE);
        }

        // 同时阻止租户管理员降级其他租户管理员（即使是同租户）——只有平台管理员可调整 TENANT_ADMIN
        if (TENANT_ADMIN_ROLE.equals(row.getRoleCode())
                && !isPlatformAdmin(currentUser)) {
            throw new MemberException(BusinessErrorCode.ROLE_NOT_ASSIGNABLE);
        }

        // 最后管理员保护：从 TENANT_ADMIN 降级为非 TENANT_ADMIN
        if (TENANT_ADMIN_ROLE.equals(row.getRoleCode())
                && !TENANT_ADMIN_ROLE.equals(newRoleCode)) {
            assertNotLastTenantAdmin(row.getTenantId(), memberId);
        }

        identityMapper.deleteUserRoles(memberId);
        identityMapper.insertUserRole(memberId, newRole.getId());
        log.info("成员角色已调整：userId={}, {} → {}", memberId, row.getRoleCode(), newRoleCode);
        return loadSummary(memberId);
    }

    // ============================================================
    // 启停 & 删除
    // ============================================================

    @Transactional
    public MemberSummary enableMember(UserAccount currentUser, Long memberId) {
        MemberRow row = loadRowOrThrow(memberId);
        assertSameTenant(currentUser, row.getTenantId());
        assertTenantWritable(row.getTenantId());
        identityMapper.setEnabled(memberId, true);
        return loadSummary(memberId);
    }

    @Transactional
    public MemberSummary disableMember(UserAccount currentUser, Long memberId) {
        MemberRow row = loadRowOrThrow(memberId);
        assertSameTenant(currentUser, row.getTenantId());
        assertTenantWritable(row.getTenantId());

        // 租户管理员不可停用任何 TENANT_ADMIN（含自己）；该操作专属平台管理员
        if (TENANT_ADMIN_ROLE.equals(row.getRoleCode()) && !isPlatformAdmin(currentUser)) {
            throw new MemberException(BusinessErrorCode.ROLE_NOT_ASSIGNABLE);
        }
        // 最后管理员保护：停用当前唯一管理员
        if (TENANT_ADMIN_ROLE.equals(row.getRoleCode()) && row.isEnabled()) {
            assertNotLastTenantAdmin(row.getTenantId(), memberId);
        }
        identityMapper.setEnabled(memberId, false);
        return loadSummary(memberId);
    }

    @Transactional
    public void deleteMember(UserAccount currentUser, Long memberId) {
        MemberRow row = loadRowOrThrow(memberId);
        assertSameTenant(currentUser, row.getTenantId());
        assertTenantWritable(row.getTenantId());

        if (TENANT_ADMIN_ROLE.equals(row.getRoleCode()) && !isPlatformAdmin(currentUser)) {
            throw new MemberException(BusinessErrorCode.ROLE_NOT_ASSIGNABLE);
        }
        if (TENANT_ADMIN_ROLE.equals(row.getRoleCode()) && row.isEnabled()) {
            assertNotLastTenantAdmin(row.getTenantId(), memberId);
        }
        identityMapper.softDelete(memberId);
        log.info("成员已软删除：userId={}, tenantId={}", memberId, row.getTenantId());
    }

    // ============================================================
    // 重置密码
    // ============================================================

    @Transactional
    public void resetPassword(UserAccount currentUser, Long memberId, String newPassword) {
        MemberRow row = loadRowOrThrow(memberId);
        assertSameTenant(currentUser, row.getTenantId());
        assertTenantWritable(row.getTenantId());

        if (TENANT_ADMIN_ROLE.equals(row.getRoleCode()) && !isPlatformAdmin(currentUser)) {
            // 租户管理员不可重置另一名管理员的密码
            throw new MemberException(BusinessErrorCode.ROLE_NOT_ASSIGNABLE);
        }
        assertPasswordStrong(newPassword);
        identityMapper.updatePasswordHash(memberId, passwordEncoder.encode(newPassword));
        log.info("成员密码已被管理员重置：userId={}, operatorId={}", memberId, currentUser.getId());
    }

    // ============================================================
    // 可分配角色
    // ============================================================

    @Transactional(readOnly = true)
    public List<RoleOption> listAssignableRoles(UserAccount currentUser) {
        // 平台管理员可分配 TENANT_ADMIN 与 TENANT_MEMBER；
        // 租户管理员仅可分配 TENANT_MEMBER。
        if (isPlatformAdmin(currentUser)) {
            return List.of(
                    new RoleOption(TENANT_ADMIN_ROLE, "租户管理员"),
                    new RoleOption(TENANT_MEMBER_ROLE, "租户成员"));
        }
        if (isTenantAdmin(currentUser)) {
            return List.of(new RoleOption(TENANT_MEMBER_ROLE, "租户成员"));
        }
        return List.of();
    }

    /**
     * 成员聚合统计。
     * 平台管理员需显式指定 tenantId；租户管理员强制使用自身 tenantId。
     * 用于成员管理页顶部指标条；不暴露敏感字段。
     */
    @Transactional(readOnly = true)
    public io.fluxora.platform.identity.mapper.MemberStats getStats(UserAccount currentUser, Long requestedTenantId) {
        Long tenantId = resolveOperableTenantId(currentUser, requestedTenantId);
        assertTenantExists(tenantId);
        return identityMapper.memberStats(tenantId);
    }

    // ============================================================
    // 内部辅助
    // ============================================================

    /**
     * 解析当前操作允许的 tenantId。
     *   - 平台管理员：必须显式传入 tenantId，传 null 抛 VALIDATION_ERROR；
     *   - 租户管理员：强制使用自身 tenantId，客户端入参被忽略；
     *   - 其他角色：直接拒绝。
     */
    private Long resolveOperableTenantId(UserAccount currentUser, Long requestedTenantId) {
        if (isPlatformAdmin(currentUser)) {
            if (requestedTenantId == null) {
                throw new MemberException(BusinessErrorCode.VALIDATION_ERROR);
            }
            return requestedTenantId;
        }
        if (isTenantAdmin(currentUser)) {
            Long ownTenantId = currentUser.getTenantId();
            if (ownTenantId == null) {
                throw new MemberException(BusinessErrorCode.CROSS_TENANT_ACCESS_DENIED);
            }
            // 即使客户端传入了不同 tenantId 也忽略，统一使用当前用户租户
            if (requestedTenantId != null && !ownTenantId.equals(requestedTenantId)) {
                throw new MemberException(BusinessErrorCode.CROSS_TENANT_ACCESS_DENIED);
            }
            return ownTenantId;
        }
        throw new MemberException(BusinessErrorCode.CROSS_TENANT_ACCESS_DENIED);
    }

    /**
     * 校验成员归属租户与操作者归属租户一致；平台管理员豁免。
     * 防止租户管理员通过成员 ID 直接绕过 URL 路径上的 tenantId 校验。
     */
    private void assertSameTenant(UserAccount currentUser, Long memberTenantId) {
        if (isPlatformAdmin(currentUser)) return;
        if (currentUser.getTenantId() == null
                || !Objects.equals(currentUser.getTenantId(), memberTenantId)) {
            throw new MemberException(BusinessErrorCode.CROSS_TENANT_ACCESS_DENIED);
        }
    }

    /** 仅校验租户存在（含已停用/过期），用于只读列表 */
    private void assertTenantExists(Long tenantId) {
        Tenant tenant = tenantMapper.findByIdIncludeDeleted(tenantId).orElse(null);
        if (tenant == null || tenant.isDeleted()) {
            throw new TenantException(BusinessErrorCode.RESOURCE_NOT_FOUND, "租户不存在");
        }
    }

    /**
     * 校验租户当前可写：已删除/已停用/已过期都禁止写操作。
     * 写操作包括创建、编辑、启停、删除成员、调整角色、重置密码。
     */
    private void assertTenantWritable(Long tenantId) {
        Tenant tenant = tenantMapper.findByIdIncludeDeleted(tenantId).orElse(null);
        if (tenant == null || tenant.isDeleted()) {
            throw new TenantException(BusinessErrorCode.RESOURCE_NOT_FOUND, "租户不存在");
        }
        if (!tenant.isEnabled()) {
            throw new MemberException(BusinessErrorCode.CROSS_TENANT_ACCESS_DENIED,
                    "租户已停用，无法对其成员进行写操作");
        }
        if (tenant.getExpireAt() != null
                && tenant.getExpireAt().isBefore(java.time.Instant.now())) {
            throw new MemberException(BusinessErrorCode.CROSS_TENANT_ACCESS_DENIED,
                    "租户已过期，无法对其成员进行写操作");
        }
    }

    /** 校验目标角色是否可被当前操作者分配 */
    private void assertRoleAssignable(UserAccount currentUser, String targetRoleCode) {
        if (PLATFORM_ADMIN_ROLE.equals(targetRoleCode)) {
            // 平台管理员角色受严格保护，本轮不允许任何接口分配
            throw new MemberException(BusinessErrorCode.ROLE_NOT_ASSIGNABLE);
        }
        if (!TENANT_SCOPE_ROLES.contains(targetRoleCode)) {
            throw new MemberException(BusinessErrorCode.ROLE_NOT_ASSIGNABLE);
        }
        if (isPlatformAdmin(currentUser)) return;
        if (isTenantAdmin(currentUser)) {
            // 租户管理员仅可分配 TENANT_MEMBER，不可创建/升级/降级 TENANT_ADMIN
            if (!TENANT_MEMBER_ROLE.equals(targetRoleCode)) {
                throw new MemberException(BusinessErrorCode.ROLE_NOT_ASSIGNABLE);
            }
            return;
        }
        throw new MemberException(BusinessErrorCode.ROLE_NOT_ASSIGNABLE);
    }

    /** 最后管理员保护：剩余有效 TENANT_ADMIN 必须 >= 1 */
    private void assertNotLastTenantAdmin(Long tenantId, Long excludeUserId) {
        long remaining = identityMapper.countActiveTenantAdmins(tenantId, excludeUserId);
        if (remaining < 1) {
            throw new MemberException(BusinessErrorCode.LAST_TENANT_ADMIN_PROTECTED);
        }
    }

    /** 密码强度：至少 8 位，含至少 1 个字母与 1 个数字 */
    private void assertPasswordStrong(String password) {
        if (password == null || password.length() < 8) {
            throw new MemberException(BusinessErrorCode.PASSWORD_WEAK);
        }
        boolean hasLetter = password.chars().anyMatch(Character::isLetter);
        boolean hasDigit = password.chars().anyMatch(Character::isDigit);
        if (!hasLetter || !hasDigit) {
            throw new MemberException(BusinessErrorCode.PASSWORD_WEAK);
        }
    }

    private MemberRow loadRowOrThrow(Long memberId) {
        return identityMapper.findMemberDetail(memberId)
                .orElseThrow(() -> new MemberException(BusinessErrorCode.MEMBER_NOT_FOUND));
    }

    private MemberSummary loadSummary(Long memberId) {
        return identityMapper.findMemberDetail(memberId)
                .map(this::toSummary)
                .orElseThrow(() -> new MemberException(BusinessErrorCode.MEMBER_NOT_FOUND));
    }

    private MemberSummary toSummary(MemberRow row) {
        return new MemberSummary(
                row.getId(), row.getUsername(), row.getDisplayName(), row.getEmail(),
                row.getRoleCode(), row.getRoleName(), row.getStatus(),
                row.getTenantId(), row.getTenantCode(), row.getTenantName(),
                row.getCreatedAt(), row.getUpdatedAt());
    }

    private boolean isPlatformAdmin(UserAccount user) {
        if (user == null) return false;
        // 平台管理员的判断使用「作用域 = PLATFORM 且持有 PLATFORM_ADMIN 角色」双重特征。
        // 这里通过角色查询以避免对 SecurityContext 的依赖。
        if (!"PLATFORM".equals(user.getScopeType())) return false;
        return identityMapper.findRolesByUserId(user.getId()).stream()
                .anyMatch(r -> PLATFORM_ADMIN_ROLE.equals(r.getCode()));
    }

    private boolean isTenantAdmin(UserAccount user) {
        if (user == null) return false;
        if (!"TENANT".equals(user.getScopeType())) return false;
        return identityMapper.findRolesByUserId(user.getId()).stream()
                .anyMatch(r -> TENANT_ADMIN_ROLE.equals(r.getCode()));
    }

    private static boolean isBlank(String s) {
        return s == null || s.isBlank();
    }

    private static String blankToNull(String s) {
        return s == null || s.isBlank() ? null : s.trim();
    }
}
