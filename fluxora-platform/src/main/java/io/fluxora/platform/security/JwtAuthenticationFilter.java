package io.fluxora.platform.security;

import io.fluxora.common.error.BusinessErrorCode;
import io.fluxora.platform.identity.entity.Permission;
import io.fluxora.platform.identity.entity.Role;
import io.fluxora.platform.identity.entity.UserAccount;
import io.fluxora.platform.identity.mapper.IdentityMapper;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.Cookie;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.List;
import java.util.stream.Collectors;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

@Component
public class JwtAuthenticationFilter extends OncePerRequestFilter {

    private static final String COOKIE_NAME = "fluxora_token";

    private final JwtService jwtService;
    private final IdentityMapper identityMapper;

    public JwtAuthenticationFilter(JwtService jwtService, IdentityMapper identityMapper) {
        this.jwtService = jwtService;
        this.identityMapper = identityMapper;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
                                    FilterChain filterChain) throws ServletException, IOException {
        String token = extractTokenFromCookie(request);
        if (token == null || !jwtService.isTokenValid(token)) {
            filterChain.doFilter(request, response);
            return;
        }

        try {
            var claims = jwtService.parseToken(token);
            Long userId = Long.parseLong(claims.getSubject());

            UserAccount user = identityMapper.findById(userId).orElse(null);
            if (user == null || !user.isEnabled()) {
                SecurityExceptionHandler.writeErrorResponse(response, 401,
                        BusinessErrorCode.AUTH_ACCOUNT_DISABLED);
                return;
            }

            List<Role> roles = identityMapper.findRolesByUserId(userId);
            List<Long> roleIds = roles.stream().map(Role::getId).collect(Collectors.toList());
            List<Permission> permissions = roleIds.isEmpty()
                    ? Collections.emptyList()
                    : identityMapper.findPermissionsByRoleIds(roleIds);

            Collection<GrantedAuthority> authorities = permissions.stream()
                    .map(p -> new SimpleGrantedAuthority("PERM_" + p.getCode()))
                    .collect(Collectors.toList());
            roles.forEach(r -> authorities.add(new SimpleGrantedAuthority("ROLE_" + r.getCode())));

            var authToken = new UsernamePasswordAuthenticationToken(user, null, authorities);
            SecurityContextHolder.getContext().setAuthentication(authToken);
        } catch (Exception e) {
            SecurityContextHolder.clearContext();
        }

        filterChain.doFilter(request, response);
    }

    private String extractTokenFromCookie(HttpServletRequest request) {
        Cookie[] cookies = request.getCookies();
        if (cookies == null) return null;
        return Arrays.stream(cookies)
                .filter(c -> COOKIE_NAME.equals(c.getName()))
                .map(Cookie::getValue)
                .findFirst()
                .orElse(null);
    }
}
