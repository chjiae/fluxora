package io.fluxora.platform.security;

import io.fluxora.common.error.BusinessErrorCode;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.List;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.CorsConfigurationSource;

/**
 * Spring Security 配置。
 * - 所有请求默认拒绝，仅显式放行 login、health、认证路径。
 * - 认证通过 JwtAuthenticationFilter 从 Cookie 解析 JWT。
 * - CORS 允许 localhost:5173（Vite dev server）。
 * - 认证入口返回 401 + 安全中文提示，禁止返回技术异常。
 */
@Configuration
@EnableMethodSecurity
public class SecurityConfig {

    private final JwtAuthenticationFilter jwtAuthenticationFilter;

    public SecurityConfig(JwtAuthenticationFilter jwtAuthenticationFilter) {
        this.jwtAuthenticationFilter = jwtAuthenticationFilter;
    }

    @Bean
    public PasswordEncoder passwordEncoder() {
        return new BCryptPasswordEncoder();
    }

    @Bean
    public SecurityFilterChain securityFilterChain(HttpSecurity http) throws Exception {
        http
            .cors(cors -> cors.configurationSource(corsConfigurationSource()))
            .csrf(csrf -> csrf.disable())
            .authorizeHttpRequests(auth -> auth
                .requestMatchers(HttpMethod.POST, "/api/auth/login").permitAll()
                .requestMatchers("/actuator/health").permitAll()
                .requestMatchers("/api/tenant/self-operated/**").authenticated()
                .requestMatchers("/api/tenant/**").authenticated()
                .requestMatchers("/api/auth/**").authenticated()
                .anyRequest().denyAll()
            )
            .exceptionHandling(ex -> ex
                .authenticationEntryPoint(this::handleAuthenticationEntryPoint)
                .accessDeniedHandler(this::handleAccessDenied)
            )
            .addFilterBefore(jwtAuthenticationFilter, UsernamePasswordAuthenticationFilter.class);

        return http.build();
    }

    private CorsConfigurationSource corsConfigurationSource() {
        return request -> {
            CorsConfiguration config = new CorsConfiguration();
            config.setAllowedOrigins(List.of("http://localhost:5173", "http://127.0.0.1:5173"));
            config.setAllowedMethods(List.of("GET", "POST", "PUT", "DELETE", "OPTIONS"));
            config.setAllowedHeaders(List.of("*"));
            config.setAllowCredentials(true);
            return config;
        };
    }

    private void handleAuthenticationEntryPoint(HttpServletRequest request,
                                                 HttpServletResponse response,
                                                 org.springframework.security.core.AuthenticationException ex)
            throws IOException {
        SecurityExceptionHandler.writeErrorResponse(response, 401,
                BusinessErrorCode.AUTH_INVALID_CREDENTIALS);
    }

    private void handleAccessDenied(HttpServletRequest request, HttpServletResponse response,
                                     org.springframework.security.access.AccessDeniedException ex)
            throws IOException {
        SecurityExceptionHandler.writeErrorResponse(response, 403,
                BusinessErrorCode.ACCESS_DENIED);
    }
}
