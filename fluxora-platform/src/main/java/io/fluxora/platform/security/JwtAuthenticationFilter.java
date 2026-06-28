package io.fluxora.platform.security;

import io.fluxora.common.error.BusinessErrorCode;
import io.fluxora.platform.identity.entity.Permission;
import io.fluxora.platform.identity.entity.Role;
import io.fluxora.platform.identity.entity.UserAccount;
import io.fluxora.platform.identity.mapper.IdentityMapper;
import io.fluxora.platform.tenant.TenantService;
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
    private final TenantService tenantService;

    public JwtAuthenticationFilter(JwtService jwtService, IdentityMapper identityMapper,
                                   TenantService tenantService) {
        this.jwtService = jwtService;
        this.identityMapper = identityMapper;
        this.tenantService = tenantService;
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
            // JWT 指向的用户已不存在（如 DB 重置后旧 token 仍有效）：清除 cookie
            // 并放行请求，由后续的登录端点或 Spring Security 入口点决定响应。
            if (user == null) {
                clearAuthCookie(response);
                filterChain.doFilter(request, response);
                return;
            }
            if (!user.isEnabled()) {
                SecurityExceptionHandler.writeErrorResponse(response, 401,
                        BusinessErrorCode.AUTH_ACCOUNT_DISABLED);
                return;
            }

            // 租户级用户每次请求都必须校验所属租户状态。
            // 如果租户已被停用、过期或删除，立即拒绝访问并返回对应的业务错误码，
            // 前端据此展示安全的中文提示（而非 401 技术文本）。
            // tenant_id 缺失的租户级用户视为无效。
            if ("TENANT".equals(user.getScopeType())) {
                if (user.getTenantId() == null) {
                    SecurityExceptionHandler.writeErrorResponse(response, 401,
                            BusinessErrorCode.AUTH_TENANT_DELETED);
                    return;
                }
                try {
                    tenantService.assertTenantValidOrThrow(user.getTenantId());
                } catch (TenantService.AuthTenantException e) {
                    SecurityExceptionHandler.writeErrorResponse(response, 401, e.getErrorCode());
                    return;
                }
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

    /**
     * 清除认证 Cookie（Max-Age=0），用于 JWT 指向的用户已不存在时
     * 让浏览器丢弃旧 token，使后续请求回到未认证状态。
     */
    private void clearAuthCookie(HttpServletResponse response) {
        Cookie cookie = new Cookie(COOKIE_NAME, "");
        cookie.setHttpOnly(true);
        cookie.setSecure(false);
        cookie.setPath("/");
        cookie.setMaxAge(0);
        response.addCookie(cookie);
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
