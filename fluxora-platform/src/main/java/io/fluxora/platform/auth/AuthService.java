package io.fluxora.platform.auth;

import io.fluxora.common.error.BusinessErrorCode;
import io.fluxora.platform.identity.entity.Permission;
import io.fluxora.platform.identity.entity.Role;
import io.fluxora.platform.identity.entity.UserAccount;
import io.fluxora.platform.identity.mapper.IdentityMapper;
import io.fluxora.platform.security.JwtService;
import io.fluxora.platform.tenant.TenantService;
import java.util.Collections;
import java.util.List;
import java.util.stream.Collectors;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class AuthService {

    private final IdentityMapper identityMapper;
    private final PasswordEncoder passwordEncoder;
    private final JwtService jwtService;
    private final TenantService tenantService;

    public AuthService(IdentityMapper identityMapper, PasswordEncoder passwordEncoder,
                       JwtService jwtService, TenantService tenantService) {
        this.identityMapper = identityMapper;
        this.passwordEncoder = passwordEncoder;
        this.jwtService = jwtService;
        this.tenantService = tenantService;
    }

    /**
     * 用户登录认证。
     * 依次校验：用户名存在 → 账号启停 → 密码匹配 → （租户用户）租户状态。
     * 租户状态异常时抛出 AuthTenantException，由全局异常处理器映射为安全中文提示。
     * 成功后签发包含 userId、username、scopeType、tenantId 的短期 JWT。
     */
    @Transactional(readOnly = true)
    public LoginResult login(String username, String rawPassword) {
        UserAccount user = identityMapper.findByUsername(username)
                .orElseThrow(() -> new AuthException(BusinessErrorCode.AUTH_INVALID_CREDENTIALS));

        if (!user.isEnabled()) {
            throw new AuthException(BusinessErrorCode.AUTH_ACCOUNT_DISABLED);
        }

        if (!passwordEncoder.matches(rawPassword, user.getPasswordHash())) {
            throw new AuthException(BusinessErrorCode.AUTH_INVALID_CREDENTIALS);
        }

        // 租户用户登录时校验所属租户状态
        if ("TENANT".equals(user.getScopeType()) && user.getTenantId() != null) {
            tenantService.assertTenantValidOrThrow(user.getTenantId());
        }

        String token = jwtService.generateToken(
                user.getId(), user.getUsername(), user.getScopeType(), user.getTenantId());

        List<Role> roles = identityMapper.findRolesByUserId(user.getId());
        List<Long> roleIds = roles.stream().map(Role::getId).collect(Collectors.toList());
        List<Permission> permissions = roleIds.isEmpty()
                ? Collections.emptyList()
                : identityMapper.findPermissionsByRoleIds(roleIds);

        List<String> permissionCodes = permissions.stream()
                .map(Permission::getCode)
                .collect(Collectors.toList());

        LoginResponse resp = new LoginResponse(
                user.getId(), user.getUsername(), user.getDisplayName(),
                user.getScopeType(), user.getTenantId(), permissionCodes);

        return new LoginResult(resp, token);
    }

    public record LoginResult(LoginResponse user, String token) {}
}
