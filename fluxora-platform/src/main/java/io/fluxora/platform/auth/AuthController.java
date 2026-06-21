package io.fluxora.platform.auth;

import io.fluxora.common.error.ErrorResponse;
import io.fluxora.common.response.ApiResponse;
import io.fluxora.platform.identity.entity.Permission;
import io.fluxora.platform.identity.entity.UserAccount;
import io.fluxora.platform.identity.mapper.IdentityMapper;
import jakarta.servlet.http.Cookie;
import jakarta.servlet.http.HttpServletResponse;
import java.util.List;
import java.util.stream.Collectors;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 认证控制器。
 * 登录签发 HttpOnly SameSite=Lax Cookie，前端不持有 Token。
 * 所有认证异常由 GlobalExceptionHandler 统一映射为安全中文错误响应。
 */
@RestController
@RequestMapping("/api/auth")
public class AuthController {

    /** Cookie 名称，前端不可通过 JS 读取 */
    private static final String COOKIE_NAME = "fluxora_token";

    private final AuthService authService;
    private final IdentityMapper identityMapper;

    public AuthController(AuthService authService, IdentityMapper identityMapper) {
        this.authService = authService;
        this.identityMapper = identityMapper;
    }

    /**
     * 登录接口（公开）。
     * 校验用户名密码 → 签发 JWT → 写入 HttpOnly Cookie → 返回用户信息和权限列表。
     * 租户用户的租户状态异常时返回 401 + 安全中文提示。
     */
    @PostMapping("/login")
    public ResponseEntity<ApiResponse<LoginResponse>> login(@RequestBody LoginRequest request,
                                                            HttpServletResponse response) {
        AuthService.LoginResult result = authService.login(request.username(), request.password());

        Cookie cookie = new Cookie(COOKIE_NAME, result.token());
        cookie.setHttpOnly(true);
        cookie.setPath("/");
        cookie.setMaxAge(86400);
        cookie.setAttribute("SameSite", "Lax");
        response.addCookie(cookie);

        return ResponseEntity.ok(ApiResponse.success(result.user()));
    }

    /** 获取当前登录用户信息和权限列表（需认证） */
    @GetMapping("/me")
    public ResponseEntity<ApiResponse<LoginResponse>> me(@AuthenticationPrincipal UserAccount user) {
        List<Permission> permissions = identityMapper.findPermissionsByUserId(user.getId());
        List<String> permissionCodes = permissions.stream()
                .map(Permission::getCode)
                .collect(Collectors.toList());

        LoginResponse resp = new LoginResponse(
                user.getId(), user.getUsername(), user.getDisplayName(),
                user.getScopeType(), user.getTenantId(), permissionCodes);

        return ResponseEntity.ok(ApiResponse.success(resp));
    }

    /** 退出登录：清除 Cookie */
    @PostMapping("/logout")
    public ResponseEntity<ApiResponse<Void>> logout(HttpServletResponse response) {
        Cookie cookie = new Cookie(COOKIE_NAME, "");
        cookie.setHttpOnly(true);
        cookie.setPath("/");
        cookie.setMaxAge(0);
        cookie.setAttribute("SameSite", "Lax");
        response.addCookie(cookie);

        return ResponseEntity.ok(ApiResponse.success(null));
    }
}
