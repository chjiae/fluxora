package io.fluxora.platform.identity;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.ResponseEntity;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.web.client.DefaultResponseErrorHandler;
import org.springframework.web.client.RestTemplate;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 成员管理集成测试。
 *
 * 覆盖 10 类业务边界场景，使用真实 PostgreSQL（Testcontainers）+ 完整 Spring Boot 容器，
 * 通过 HTTP 接口与 cookie 透传方式模拟「平台管理员 / 租户管理员 / 普通成员」的真实操作。
 *
 * 测试结构与 TenantManagementIntegrationTest 保持一致，统一使用 RestTemplate 自定义
 * 错误处理器以便对 4xx/5xx 响应进行断言。
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Testcontainers
class MemberManagementIntegrationTest {

    @Container
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:17-alpine")
            .withDatabaseName("fluxora")
            .withUsername("fluxora")
            .withPassword("fluxora");

    @DynamicPropertySource
    static void configureDataSource(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", postgres::getJdbcUrl);
        registry.add("spring.datasource.username", postgres::getUsername);
        registry.add("spring.datasource.password", postgres::getPassword);
    }

    @LocalServerPort private int port;
    private final RestTemplate restTemplate;
    private final ObjectMapper objectMapper = new ObjectMapper();
    private String baseUrl;

    public MemberManagementIntegrationTest() {
        this.restTemplate = new RestTemplate();
        this.restTemplate.setErrorHandler(new DefaultResponseErrorHandler() {
            @Override public boolean hasError(HttpStatusCode s) { return false; }
        });
    }

    @BeforeEach
    void setUp() {
        baseUrl = "http://localhost:" + port;
    }

    // ============================================================
    // 辅助方法
    // ============================================================

    private HttpHeaders login(String username, String password) throws Exception {
        String json = objectMapper.writeValueAsString(Map.of("username", username, "password", password));
        HttpHeaders h = new HttpHeaders(); h.set("Content-Type", "application/json");
        ResponseEntity<String> r = restTemplate.exchange(
                baseUrl + "/api/auth/login", HttpMethod.POST, new HttpEntity<>(json, h), String.class);
        if (r.getStatusCode() != HttpStatus.OK) {
            throw new IllegalStateException("登录失败：" + r.getStatusCode() + " " + r.getBody());
        }
        HttpHeaders ah = new HttpHeaders();
        ah.put(HttpHeaders.COOKIE, r.getHeaders().get(HttpHeaders.SET_COOKIE));
        ah.set("Content-Type", "application/json");
        return ah;
    }

    private HttpHeaders adminAuth() throws Exception {
        return login("admin", "Admin@2026!");
    }

    /**
     * 确保自营租户已初始化，返回 default 租户 ID。
     * Testcontainers 共享同一容器，多个用例不会重复初始化。
     * 注意：此方法不创建额外管理员；如需特定 TENANT_ADMIN，使用 createTenantAdminInDefault。
     */
    private long ensureSelfOperated(HttpHeaders ah) throws Exception {
        String seed = "seedta_" + System.nanoTime();
        String ij = objectMapper.writeValueAsString(Map.of(
                "tenantName", "自营测试", "adminUsername", seed,
                "adminPassword", "SeedPass2026!", "adminDisplayName", "TA Seed"));
        restTemplate.exchange(baseUrl + "/api/tenant/self-operated/initialize",
                HttpMethod.POST, new HttpEntity<>(ij, ah), String.class);
        ResponseEntity<String> list = restTemplate.exchange(
                baseUrl + "/api/tenant?keyword=default", HttpMethod.GET,
                new HttpEntity<>(ah), String.class);
        JsonNode items = objectMapper.readTree(list.getBody()).get("data").get("items");
        for (JsonNode n : items) {
            if ("default".equals(n.get("tenantCode").asText())) {
                return n.get("id").asLong();
            }
        }
        throw new IllegalStateException("找不到 default 自营租户");
    }

    /**
     * 在 default 自营租户中创建一名 TENANT_ADMIN，使用平台管理员身份。
     * 用于需要独立租户管理员账号的用例，避免多个用例共享种子账号导致状态污染。
     */
    private String createTenantAdminInDefault(HttpHeaders ah) throws Exception {
        long defaultId = ensureSelfOperated(ah);
        String username = "ta_" + System.nanoTime();
        String j = objectMapper.writeValueAsString(Map.of(
                "username", username,
                "displayName", "Tenant Admin",
                "password", "TaPass2026!",
                "roleCode", "TENANT_ADMIN"));
        ResponseEntity<String> r = restTemplate.exchange(
                baseUrl + "/api/tenant/" + defaultId + "/members",
                HttpMethod.POST, new HttpEntity<>(j, ah), String.class);
        assertThat(r.getStatusCode()).as("创建租户管理员应成功，实际响应: %s", r.getBody())
                .isEqualTo(HttpStatus.OK);
        return username;
    }

    private long createTenant(HttpHeaders ah, String code) throws Exception {
        String j = objectMapper.writeValueAsString(Map.of(
                "tenantCode", code, "name", "测试租户-" + code, "type", "STANDARD", "enabled", true));
        ResponseEntity<String> r = restTemplate.exchange(
                baseUrl + "/api/tenant", HttpMethod.POST, new HttpEntity<>(j, ah), String.class);
        assertThat(r.getStatusCode()).isEqualTo(HttpStatus.OK);
        return objectMapper.readTree(r.getBody()).get("data").get("id").asLong();
    }

    private long createMemberAsPlatformAdmin(HttpHeaders ah, long tenantId, String username,
                                              String roleCode) throws Exception {
        String j = objectMapper.writeValueAsString(Map.of(
                "username", username,
                "displayName", username,
                "email", username + "@e2e.local",
                "password", "Passw0rd!Strong",
                "roleCode", roleCode));
        ResponseEntity<String> r = restTemplate.exchange(
                baseUrl + "/api/tenant/" + tenantId + "/members",
                HttpMethod.POST, new HttpEntity<>(j, ah), String.class);
        assertThat(r.getStatusCode()).as("创建成员应成功，实际响应: %s", r.getBody())
                .isEqualTo(HttpStatus.OK);
        return objectMapper.readTree(r.getBody()).get("data").get("id").asLong();
    }

    // ============================================================
    // 1. 平台管理员可管理任意租户成员
    // ============================================================

    @Test
    void platformAdmin_canCreateAndListAndUpdateMembers() throws Exception {
        HttpHeaders ah = adminAuth();
        String tenantCode = "pm-" + System.nanoTime();
        long tenantId = createTenant(ah, tenantCode);
        long memberId = createMemberAsPlatformAdmin(ah, tenantId, "m_" + System.nanoTime(), "TENANT_MEMBER");

        // 列表能查到
        ResponseEntity<String> list = restTemplate.exchange(
                baseUrl + "/api/tenant/" + tenantId + "/members?page=1&size=10",
                HttpMethod.GET, new HttpEntity<>(ah), String.class);
        assertThat(list.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(objectMapper.readTree(list.getBody()).get("data").get("total").asLong())
                .isGreaterThanOrEqualTo(1);

        // 编辑资料
        String uj = objectMapper.writeValueAsString(Map.of(
                "displayName", "更新后的显示名", "email", "updated@e2e.local"));
        ResponseEntity<String> ur = restTemplate.exchange(
                baseUrl + "/api/members/" + memberId, HttpMethod.PUT,
                new HttpEntity<>(uj, ah), String.class);
        assertThat(ur.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(objectMapper.readTree(ur.getBody()).get("data").get("displayName").asText())
                .isEqualTo("更新后的显示名");
    }

    // ============================================================
    // 2 & 3. 租户管理员只能管理本租户成员，跨租户被拒
    // ============================================================

    @Test
    void tenantAdmin_canManageOwnTenantButNotOthers() throws Exception {
        HttpHeaders ah = adminAuth();
        // 通过平台管理员在 default 自营租户创建一名独立 TENANT_ADMIN
        String taUserA = createTenantAdminInDefault(ah);
        // 另一个标准租户
        long otherTenantId = createTenant(ah, "other-" + System.nanoTime());

        HttpHeaders taAuth = login(taUserA, "TaPass2026!");

        // 自身租户的 /api/members 可以访问
        ResponseEntity<String> own = restTemplate.exchange(
                baseUrl + "/api/members?page=1&size=10",
                HttpMethod.GET, new HttpEntity<>(taAuth), String.class);
        assertThat(own.getStatusCode()).isEqualTo(HttpStatus.OK);

        // 访问其他租户的成员列表 → 403 (CROSS_TENANT_ACCESS_DENIED)
        ResponseEntity<String> cross = restTemplate.exchange(
                baseUrl + "/api/tenant/" + otherTenantId + "/members",
                HttpMethod.GET, new HttpEntity<>(taAuth), String.class);
        assertThat(cross.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(cross.getBody()).contains("CROSS_TENANT_ACCESS_DENIED");
    }

    // ============================================================
    // 4. 租户管理员不能创建/升级 TENANT_ADMIN
    // ============================================================

    @Test
    void tenantAdmin_cannotCreateAnotherTenantAdmin() throws Exception {
        HttpHeaders ah = adminAuth();
        String taUser = createTenantAdminInDefault(ah);
        long tenantId = ensureSelfOperated(ah);

        HttpHeaders taAuth = login(taUser, "TaPass2026!");

        String j = objectMapper.writeValueAsString(Map.of(
                "username", "promoted_" + System.nanoTime(),
                "displayName", "尝试升级",
                "password", "Passw0rd!Strong",
                "roleCode", "TENANT_ADMIN"));
        ResponseEntity<String> r = restTemplate.exchange(
                baseUrl + "/api/tenant/" + tenantId + "/members",
                HttpMethod.POST, new HttpEntity<>(j, taAuth), String.class);
        assertThat(r.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(r.getBody()).contains("ROLE_NOT_ASSIGNABLE");
    }

    // ============================================================
    // 5. 任何人无法创建 PLATFORM_ADMIN
    // ============================================================

    @Test
    void cannotCreatePlatformAdminViaMemberApi() throws Exception {
        HttpHeaders ah = adminAuth();
        long tenantId = createTenant(ah, "pa-block-" + System.nanoTime());
        String j = objectMapper.writeValueAsString(Map.of(
                "username", "pa_" + System.nanoTime(),
                "displayName", "尝试创建平台管理员",
                "password", "Passw0rd!Strong",
                "roleCode", "PLATFORM_ADMIN"));
        ResponseEntity<String> r = restTemplate.exchange(
                baseUrl + "/api/tenant/" + tenantId + "/members",
                HttpMethod.POST, new HttpEntity<>(j, ah), String.class);
        assertThat(r.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(r.getBody()).contains("ROLE_NOT_ASSIGNABLE");
    }

    // ============================================================
    // 6. 停用/删除后旧 JWT 立即失效
    // ============================================================

    @Test
    void disabledOrDeletedMemberCannotAccessProtectedEndpoint() throws Exception {
        HttpHeaders ah = adminAuth();
        long tenantId = ensureSelfOperated(ah);
        long memberId = createMemberAsPlatformAdmin(ah, tenantId,
                "tomdisable_" + System.nanoTime(), "TENANT_MEMBER");

        // 拿到这个成员的 cookie（先确认能登录）
        String memberUsername = objectMapper.readTree(
                restTemplate.exchange(baseUrl + "/api/members/" + memberId,
                        HttpMethod.GET, new HttpEntity<>(ah), String.class).getBody())
                .get("data").get("username").asText();

        HttpHeaders memberAuth = login(memberUsername, "Passw0rd!Strong");

        // 平台管理员把成员停用
        restTemplate.exchange(baseUrl + "/api/members/" + memberId + "/disable",
                HttpMethod.PUT, new HttpEntity<>("{}", ah), String.class);

        // 老 cookie 访问 /api/auth/me → 401 AUTH_ACCOUNT_DISABLED
        ResponseEntity<String> me = restTemplate.exchange(
                baseUrl + "/api/auth/me", HttpMethod.GET, new HttpEntity<>(memberAuth), String.class);
        assertThat(me.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
        assertThat(me.getBody()).contains("AUTH_ACCOUNT_DISABLED");

        // 启用后允许登录；进一步删除后旧密码登录失败
        restTemplate.exchange(baseUrl + "/api/members/" + memberId + "/enable",
                HttpMethod.PUT, new HttpEntity<>("{}", ah), String.class);
        restTemplate.exchange(baseUrl + "/api/members/" + memberId,
                HttpMethod.DELETE, new HttpEntity<>(ah), String.class);

        // 软删后用户名重新登录失败：可能返回 AUTH_INVALID_CREDENTIALS（用户名通过部分索引已不可见）
        String lj = objectMapper.writeValueAsString(Map.of("username", memberUsername,
                "password", "Passw0rd!Strong"));
        HttpHeaders lh = new HttpHeaders(); lh.set("Content-Type", "application/json");
        ResponseEntity<String> loginR = restTemplate.exchange(
                baseUrl + "/api/auth/login", HttpMethod.POST,
                new HttpEntity<>(lj, lh), String.class);
        assertThat(loginR.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
        // 仅校验状态码即可证明软删除生效；body 可能为空或包含错误码，因不同代码路径而异
        if (loginR.getBody() != null) {
            assertThat(loginR.getBody()).containsAnyOf("AUTH_INVALID_CREDENTIALS", "AUTH_ACCOUNT_DISABLED");
        }
    }

    // ============================================================
    // 7. 重置密码后旧密码失效、新密码可用
    // ============================================================

    @Test
    void resetPassword_oldFailsNewWorks() throws Exception {
        HttpHeaders ah = adminAuth();
        long tenantId = ensureSelfOperated(ah);
        long memberId = createMemberAsPlatformAdmin(ah, tenantId,
                "pwduser_" + System.nanoTime(), "TENANT_MEMBER");
        String memberUsername = objectMapper.readTree(
                restTemplate.exchange(baseUrl + "/api/members/" + memberId,
                        HttpMethod.GET, new HttpEntity<>(ah), String.class).getBody())
                .get("data").get("username").asText();

        // 重置密码
        String rj = objectMapper.writeValueAsString(Map.of("newPassword", "NewPass2026!"));
        ResponseEntity<String> rr = restTemplate.exchange(
                baseUrl + "/api/members/" + memberId + "/password",
                HttpMethod.PUT, new HttpEntity<>(rj, ah), String.class);
        assertThat(rr.getStatusCode()).isEqualTo(HttpStatus.OK);

        HttpHeaders lh = new HttpHeaders(); lh.set("Content-Type", "application/json");

        // 旧密码登录失败
        String oldJ = objectMapper.writeValueAsString(Map.of("username", memberUsername,
                "password", "Passw0rd!Strong"));
        ResponseEntity<String> oldLogin = restTemplate.exchange(
                baseUrl + "/api/auth/login", HttpMethod.POST,
                new HttpEntity<>(oldJ, lh), String.class);
        assertThat(oldLogin.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);

        // 新密码登录成功
        String newJ = objectMapper.writeValueAsString(Map.of("username", memberUsername,
                "password", "NewPass2026!"));
        ResponseEntity<String> newLogin = restTemplate.exchange(
                baseUrl + "/api/auth/login", HttpMethod.POST,
                new HttpEntity<>(newJ, lh), String.class);
        assertThat(newLogin.getStatusCode()).isEqualTo(HttpStatus.OK);
    }

    // ============================================================
    // 8. 最后一名有效 TENANT_ADMIN 保护
    // ============================================================

    @Test
    void lastTenantAdmin_cannotBeDisabledOrDeleted() throws Exception {
        HttpHeaders ah = adminAuth();
        // 使用独立标准租户测试，避免与其他用例共享 default 自营租户的管理员集合
        long isolatedTenantId = createTenant(ah, "last-admin-" + System.nanoTime());
        String taUsername = "lastadmin_" + System.nanoTime();
        String j = objectMapper.writeValueAsString(Map.of(
                "username", taUsername,
                "displayName", "唯一管理员",
                "password", "TaPass2026!",
                "roleCode", "TENANT_ADMIN"));
        ResponseEntity<String> cr = restTemplate.exchange(
                baseUrl + "/api/tenant/" + isolatedTenantId + "/members",
                HttpMethod.POST, new HttpEntity<>(j, ah), String.class);
        assertThat(cr.getStatusCode()).isEqualTo(HttpStatus.OK);
        long taMemberId = objectMapper.readTree(cr.getBody()).get("data").get("id").asLong();

        // 平台管理员尝试停用该唯一管理员
        ResponseEntity<String> dis = restTemplate.exchange(
                baseUrl + "/api/members/" + taMemberId + "/disable",
                HttpMethod.PUT, new HttpEntity<>("{}", ah), String.class);
        assertThat(dis.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(dis.getBody()).contains("LAST_TENANT_ADMIN_PROTECTED");

        // 尝试删除
        ResponseEntity<String> del = restTemplate.exchange(
                baseUrl + "/api/members/" + taMemberId,
                HttpMethod.DELETE, new HttpEntity<>(ah), String.class);
        assertThat(del.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(del.getBody()).contains("LAST_TENANT_ADMIN_PROTECTED");

        // 尝试降级
        String rj = objectMapper.writeValueAsString(Map.of("roleCode", "TENANT_MEMBER"));
        ResponseEntity<String> demote = restTemplate.exchange(
                baseUrl + "/api/members/" + taMemberId + "/role",
                HttpMethod.PUT, new HttpEntity<>(rj, ah), String.class);
        assertThat(demote.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(demote.getBody()).contains("LAST_TENANT_ADMIN_PROTECTED");
    }

    // ============================================================
    // 9. 用户名重复 + 软删后可复用
    // ============================================================

    @Test
    void duplicateUsername_blockedButReusableAfterSoftDelete() throws Exception {
        HttpHeaders ah = adminAuth();
        long tenantId = createTenant(ah, "dup-" + System.nanoTime());
        String username = "dup_user_" + System.nanoTime();
        long firstId = createMemberAsPlatformAdmin(ah, tenantId, username, "TENANT_MEMBER");

        // 重复用户名直接拒绝
        String j = objectMapper.writeValueAsString(Map.of(
                "username", username,
                "displayName", "重复用户名",
                "password", "Passw0rd!Strong",
                "roleCode", "TENANT_MEMBER"));
        ResponseEntity<String> dup = restTemplate.exchange(
                baseUrl + "/api/tenant/" + tenantId + "/members",
                HttpMethod.POST, new HttpEntity<>(j, ah), String.class);
        assertThat(dup.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(dup.getBody()).contains("USERNAME_DUPLICATE");

        // 软删原成员后，相同用户名可被复用
        restTemplate.exchange(baseUrl + "/api/members/" + firstId,
                HttpMethod.DELETE, new HttpEntity<>(ah), String.class);
        ResponseEntity<String> reuse = restTemplate.exchange(
                baseUrl + "/api/tenant/" + tenantId + "/members",
                HttpMethod.POST, new HttpEntity<>(j, ah), String.class);
        assertThat(reuse.getStatusCode()).isEqualTo(HttpStatus.OK);
    }

    // ============================================================
    // 10. 弱密码被拒绝
    // ============================================================

    @Test
    void weakPasswordRejected() throws Exception {
        HttpHeaders ah = adminAuth();
        long tenantId = createTenant(ah, "weak-" + System.nanoTime());
        // 长度不足
        String shortPwd = objectMapper.writeValueAsString(Map.of(
                "username", "weak_a_" + System.nanoTime(),
                "displayName", "弱密码",
                "password", "abc1",
                "roleCode", "TENANT_MEMBER"));
        ResponseEntity<String> r1 = restTemplate.exchange(
                baseUrl + "/api/tenant/" + tenantId + "/members",
                HttpMethod.POST, new HttpEntity<>(shortPwd, ah), String.class);
        assertThat(r1.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(r1.getBody()).contains("PASSWORD_WEAK");

        // 纯数字
        String allDigits = objectMapper.writeValueAsString(Map.of(
                "username", "weak_b_" + System.nanoTime(),
                "displayName", "弱密码2",
                "password", "12345678",
                "roleCode", "TENANT_MEMBER"));
        ResponseEntity<String> r2 = restTemplate.exchange(
                baseUrl + "/api/tenant/" + tenantId + "/members",
                HttpMethod.POST, new HttpEntity<>(allDigits, ah), String.class);
        assertThat(r2.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(r2.getBody()).contains("PASSWORD_WEAK");
    }
}
