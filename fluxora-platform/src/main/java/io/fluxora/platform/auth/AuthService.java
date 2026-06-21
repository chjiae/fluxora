package io.fluxora.platform.auth;

import io.fluxora.common.error.BusinessErrorCode;
import io.fluxora.platform.identity.entity.Permission;
import io.fluxora.platform.identity.entity.Role;
import io.fluxora.platform.identity.entity.UserAccount;
import io.fluxora.platform.identity.mapper.IdentityMapper;
import io.fluxora.platform.security.JwtService;
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

    public AuthService(IdentityMapper identityMapper, PasswordEncoder passwordEncoder,
                       JwtService jwtService) {
        this.identityMapper = identityMapper;
        this.passwordEncoder = passwordEncoder;
        this.jwtService = jwtService;
    }

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
