package io.fluxora.platform.identity.mapper;

import io.fluxora.platform.identity.entity.Permission;
import io.fluxora.platform.identity.entity.Role;
import io.fluxora.platform.identity.entity.UserAccount;
import java.util.List;
import java.util.Optional;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

/**
 * 用户身份与权限相关的 MyBatis Mapper。
 * 所有 SQL 均写在对应的 IdentityMapper.xml 中，禁止注解 SQL。
 *
 * 软删除约定：
 *   - findByUsername / findById / existsByUsername 默认过滤 deleted_at IS NULL；
 *     软删除用户对认证、JWT 过滤器、登录都不可见。
 *   - findByIdIncludeDeleted 显式包含已删除记录，目前未启用，预留供未来恢复/审计流程使用。
 */
@Mapper
public interface IdentityMapper {

    /** 按用户名查询用户账号（登录认证用），仅未删除记录 */
    Optional<UserAccount> findByUsername(@Param("username") String username);

    /** 按 ID 查询用户账号（JWT 过滤器用），仅未删除记录 */
    Optional<UserAccount> findById(@Param("id") Long id);

    /** 查询用户拥有的角色列表 */
    List<Role> findRolesByUserId(@Param("userId") Long userId);

    /** 查询用户通过角色间接拥有的权限列表 */
    List<Permission> findPermissionsByUserId(@Param("userId") Long userId);

    /** 按角色 ID 列表批量查询权限（避免逐角色查询的 N+1 问题） */
    List<Permission> findPermissionsByRoleIds(@Param("roleIds") List<Long> roleIds);

    /** 按角色编码查询角色 */
    Optional<Role> findRoleByCode(@Param("code") String code);

    /** 检查用户名是否已存在（仅未删除记录） */
    boolean existsByUsername(@Param("username") String username);

    /** 插入用户账号，使用数据库自增主键 */
    void insertUser(UserAccount user);

    /** 插入用户角色关联 */
    void insertUserRole(@Param("userId") Long userId, @Param("roleId") Long roleId);

    // ============================================================
    // 成员管理扩展（MemberService 调用）
    // ============================================================

    /**
     * 按租户分页查询成员。tenantId 为必填，禁止跨租户；keyword/status/roleCode 为可选过滤。
     * status 取值：ENABLED / DISABLED（DELETED 不应通过查询展示——已被默认过滤）。
     * 返回结果按 created_at DESC, id DESC 排序，保证分页稳定。
     */
    List<MemberRow> findMembers(@Param("tenantId") Long tenantId,
                                @Param("keyword") String keyword,
                                @Param("status") String status,
                                @Param("roleCode") String roleCode,
                                @Param("offset") int offset,
                                @Param("limit") int limit);

    /** 统计符合条件的成员总数（用于分页） */
    long countMembers(@Param("tenantId") Long tenantId,
                      @Param("keyword") String keyword,
                      @Param("status") String status,
                      @Param("roleCode") String roleCode);

    /** 按 ID 查询单个成员（含角色信息），仅未删除记录 */
    Optional<MemberRow> findMemberDetail(@Param("id") Long id);

    /** 更新成员基础资料（display_name、email） */
    void updateProfile(@Param("id") Long id,
                       @Param("displayName") String displayName,
                       @Param("email") String email);

    /** 更新成员密码哈希 */
    void updatePasswordHash(@Param("id") Long id, @Param("passwordHash") String passwordHash);

    /** 设置成员启用/停用状态 */
    void setEnabled(@Param("id") Long id, @Param("enabled") boolean enabled);

    /** 软删除成员：写入 deleted_at = NOW()，幂等保证仅对未删除记录执行 */
    void softDelete(@Param("id") Long id);

    /** 删除指定用户的全部角色关联，用于角色替换前的清理 */
    void deleteUserRoles(@Param("userId") Long userId);

    /**
     * 统计某租户内启用状态的 TENANT_ADMIN 数量，支持排除某个用户。
     * 用于「最后管理员保护」：检查停用/删除/降级目标后是否还有有效租户管理员。
     * excludeUserId 为 null 时不排除任何用户。
     */
    long countActiveTenantAdmins(@Param("tenantId") Long tenantId,
                                 @Param("excludeUserId") Long excludeUserId);

    /**
     * 成员聚合统计：一次 SQL 返回某租户的成员计数（总数、启用、停用、租户管理员、普通成员）。
     * 已软删成员不计入；用于成员管理页的指标条，避免前端为统计多次往返。
     */
    MemberStats memberStats(@Param("tenantId") Long tenantId);
}
