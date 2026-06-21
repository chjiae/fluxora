package io.fluxora.platform.identity.mapper;

import io.fluxora.platform.identity.entity.Permission;
import io.fluxora.platform.identity.entity.Role;
import io.fluxora.platform.identity.entity.UserAccount;
import java.util.List;
import java.util.Optional;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

@Mapper
public interface IdentityMapper {

    Optional<UserAccount> findByUsername(@Param("username") String username);

    Optional<UserAccount> findById(@Param("id") Long id);

    List<Role> findRolesByUserId(@Param("userId") Long userId);

    List<Permission> findPermissionsByUserId(@Param("userId") Long userId);

    List<Permission> findPermissionsByRoleIds(@Param("roleIds") List<Long> roleIds);

    Optional<Role> findRoleByCode(@Param("code") String code);

    boolean existsByUsername(@Param("username") String username);

    void insertUser(UserAccount user);

    void insertUserRole(@Param("userId") Long userId, @Param("roleId") Long roleId);
}
