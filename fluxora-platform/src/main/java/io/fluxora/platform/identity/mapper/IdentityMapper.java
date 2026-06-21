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
 */
@Mapper
public interface IdentityMapper {

    /** 按用户名查询用户账号（登录认证用） */
    Optional<UserAccount> findByUsername(@Param("username") String username);

    /** 按 ID 查询用户账号（JWT 过滤器用） */
    Optional<UserAccount> findById(@Param("id") Long id);

    /** 查询用户拥有的角色列表 */
    List<Role> findRolesByUserId(@Param("userId") Long userId);

    /** 查询用户通过角色间接拥有的权限列表 */
    List<Permission> findPermissionsByUserId(@Param("userId") Long userId);

    /** 按角色 ID 列表批量查询权限（避免逐角色查询的 N+1 问题） */
    List<Permission> findPermissionsByRoleIds(@Param("roleIds") List<Long> roleIds);

    /** 按角色编码查询角色 */
    Optional<Role> findRoleByCode(@Param("code") String code);

    /** 检查用户名是否已存在（初始化幂等性检查用） */
    boolean existsByUsername(@Param("username") String username);

    /** 插入用户账号，使用数据库自增主键 */
    void insertUser(UserAccount user);

    /** 插入用户角色关联 */
    void insertUserRole(@Param("userId") Long userId, @Param("roleId") Long roleId);
}
