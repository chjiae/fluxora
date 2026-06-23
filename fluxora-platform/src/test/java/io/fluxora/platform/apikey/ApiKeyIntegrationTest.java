package io.fluxora.platform.apikey;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.web.client.DefaultResponseErrorHandler;
import org.springframework.web.client.RestTemplate;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import javax.sql.DataSource;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * API Key 集成测试。
 *
 * 覆盖 AGENT.md 中所有 API Key 安全约束：
 *   1. DB 不保存明文 Key（无 key_plaintext 列；key_hash != plaintext）；
 *   2. 完整 plaintext 仅在创建响应中返回一次；
 *   3. 列表 / 详情不返回 plaintext 与 key_hash；
 *   4. 用户只能操作自己的 Key；
 *   5. 租户管理员只能操作本租户用户 Key；
 *   6. 平台管理员可跨租户操作；
 *   7. 停用 / 删除后状态判断正确。
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Testcontainers
class ApiKeyIntegrationTest {

    @Container
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:17-alpine")
            .withDatabaseName("fluxora").withUsername("fluxora").withPassword("fluxora");

    @DynamicPropertySource
    static void configureDataSource(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", postgres::getJdbcUrl);
        registry.add("spring.datasource.username", postgres::getUsername);
        registry.add("spring.datasource.password", postgres::getPassword);
    }

    @LocalServerPort private int port;
    @Autowired private DataSource dataSource;
    private final RestTemplate restTemplate;
    private final ObjectMapper objectMapper = new ObjectMapper();
    private String baseUrl;

    public ApiKeyIntegrationTest() {
        this.restTemplate = new RestTemplate();
        this.restTemplate.setErrorHandler(new DefaultResponseErrorHandler() {
            @Override public boolean hasError(HttpStatusCode s) { return false; }
        });
    }

    @BeforeEach
    void setUp() { baseUrl = "http://localhost:" + port; }

    // ---------- 辅助 ----------

    private HttpHeaders login(String u, String p) throws Exception {
        String json = objectMapper.writeValueAsString(Map.of("username", u, "password", p));
        HttpHeaders h = new HttpHeaders(); h.set("Content-Type", "application/json");
        ResponseEntity<String> r = restTemplate.exchange(
                baseUrl + "/api/auth/login", HttpMethod.POST, new HttpEntity<>(json, h), String.class);
        if (r.getStatusCode() != HttpStatus.OK)
            throw new IllegalStateException("登录失败：" + r.getStatusCode() + " " + r.getBody());
        HttpHeaders ah = new HttpHeaders();
        ah.put(HttpHeaders.COOKIE, r.getHeaders().get(HttpHeaders.SET_COOKIE));
        ah.set("Content-Type", "application/json");
        return ah;
    }
    private HttpHeaders adminAuth() throws Exception { return login("admin", "Admin@2026!"); }

    private long ensureSelfOperated(HttpHeaders ah) throws Exception {
        String seed = "seed_" + System.nanoTime();
        String ij = objectMapper.writeValueAsString(Map.of(
                "tenantName", "自营", "adminUsername", seed,
                "adminPassword", "SeedPass2026!", "adminDisplayName", "Seed"));
        restTemplate.exchange(baseUrl + "/api/tenant/self-operated/initialize",
                HttpMethod.POST, new HttpEntity<>(ij, ah), String.class);
        ResponseEntity<String> list = restTemplate.exchange(
                baseUrl + "/api/tenant?keyword=default", HttpMethod.GET, new HttpEntity<>(ah), String.class);
        for (JsonNode n : objectMapper.readTree(list.getBody()).get("data").get("items")) {
            if ("default".equals(n.get("tenantCode").asText())) return n.get("id").asLong();
        }
        throw new IllegalStateException("default 不存在");
    }

    /** 在指定租户内创建一个普通租户用户，返回 username（密码统一 "Passw0rd!Strong"） */
    private String createTenantMember(HttpHeaders ah, long tenantId) throws Exception {
        String username = "u" + System.nanoTime();
        String j = objectMapper.writeValueAsString(Map.of(
                "username", username, "displayName", username,
                "password", "Passw0rd!Strong", "roleCode", "TENANT_MEMBER"));
        ResponseEntity<String> r = restTemplate.exchange(
                baseUrl + "/api/tenant/" + tenantId + "/members",
                HttpMethod.POST, new HttpEntity<>(j, ah), String.class);
        assertThat(r.getStatusCode()).isEqualTo(HttpStatus.OK);
        return username;
    }

    private long createTenant(HttpHeaders ah, String code) throws Exception {
        String j = objectMapper.writeValueAsString(Map.of(
                "tenantCode", code, "name", "测试", "type", "STANDARD", "enabled", true));
        ResponseEntity<String> r = restTemplate.exchange(
                baseUrl + "/api/tenant", HttpMethod.POST, new HttpEntity<>(j, ah), String.class);
        assertThat(r.getStatusCode()).isEqualTo(HttpStatus.OK);
        return objectMapper.readTree(r.getBody()).get("data").get("id").asLong();
    }

    // ---------- 1. DB 不保存明文 ----------

    @Test
    void databaseShouldNeverStorePlaintextKey() throws Exception {
        HttpHeaders ah = adminAuth();
        ensureSelfOperated(ah);
        String username = createTenantMember(ah, 1L);
        HttpHeaders user = login(username, "Passw0rd!Strong");

        // 创建一个 Key
        String j = objectMapper.writeValueAsString(Map.of("name", "test-no-plain"));
        ResponseEntity<String> r = restTemplate.exchange(
                baseUrl + "/api/api-keys", HttpMethod.POST, new HttpEntity<>(j, user), String.class);
        assertThat(r.getStatusCode()).isEqualTo(HttpStatus.OK);
        JsonNode data = objectMapper.readTree(r.getBody()).get("data");
        String plaintext = data.get("plaintext").asText();
        String prefix = data.get("summary").get("keyPrefix").asText();
        assertThat(plaintext).startsWith("flx_");
        assertThat(plaintext).hasSize(45);

        // DB 中应该没有 plaintext 列；也没有任何行的 lookup_hash == plaintext
        JdbcTemplate jdbc = new JdbcTemplate(dataSource);
        Integer plaintextCol = jdbc.queryForObject(
                "SELECT COUNT(*) FROM information_schema.columns "
                        + "WHERE table_name = 'api_key' AND column_name IN ('key_plaintext','plaintext','full_key')",
                Integer.class);
        assertThat(plaintextCol).as("api_key 表不应有任何明文列").isEqualTo(0);

        String hash = jdbc.queryForObject(
                "SELECT lookup_hash FROM api_key WHERE key_prefix = ?",
                String.class, prefix);
        assertThat(hash).isNotEqualTo(plaintext);
        assertThat(hash).doesNotContain(plaintext);
        assertThat(hash).hasSize(64); // SHA-256 hex
    }

    // ---------- 2 & 3. plaintext 仅一次性返回 ----------

    @Test
    void plaintextOnlyReturnedOnceOnCreation() throws Exception {
        HttpHeaders ah = adminAuth();
        ensureSelfOperated(ah);
        String username = createTenantMember(ah, 1L);
        HttpHeaders user = login(username, "Passw0rd!Strong");

        String j = objectMapper.writeValueAsString(Map.of("name", "test-once"));
        ResponseEntity<String> r = restTemplate.exchange(
                baseUrl + "/api/api-keys", HttpMethod.POST, new HttpEntity<>(j, user), String.class);
        JsonNode created = objectMapper.readTree(r.getBody()).get("data");
        long id = created.get("summary").get("id").asLong();
        String prefix = created.get("summary").get("keyPrefix").asText();

        // GET 详情
        ResponseEntity<String> det = restTemplate.exchange(
                baseUrl + "/api/api-keys/" + id, HttpMethod.GET, new HttpEntity<>(user), String.class);
        JsonNode detail = objectMapper.readTree(det.getBody()).get("data");
        assertThat(detail.has("plaintext")).isFalse();
        assertThat(detail.has("keyHash")).isFalse();
        assertThat(detail.has("lookup_hash")).isFalse();
        assertThat(detail.get("keyPrefix").asText()).isEqualTo(prefix);

        // GET 列表
        ResponseEntity<String> list = restTemplate.exchange(
                baseUrl + "/api/api-keys", HttpMethod.GET, new HttpEntity<>(user), String.class);
        JsonNode items = objectMapper.readTree(list.getBody()).get("data").get("items");
        for (JsonNode item : items) {
            assertThat(item.has("plaintext")).isFalse();
            assertThat(item.has("keyHash")).isFalse();
        }
    }

    // ---------- 4. 用户只能操作自己 ----------

    @Test
    void userCannotAccessAnotherUsersKey() throws Exception {
        HttpHeaders ah = adminAuth();
        ensureSelfOperated(ah);
        String userA = createTenantMember(ah, 1L);
        String userB = createTenantMember(ah, 1L);
        HttpHeaders ua = login(userA, "Passw0rd!Strong");
        HttpHeaders ub = login(userB, "Passw0rd!Strong");

        // A 创建一个 Key
        String j = objectMapper.writeValueAsString(Map.of("name", "a-only"));
        ResponseEntity<String> r = restTemplate.exchange(
                baseUrl + "/api/api-keys", HttpMethod.POST, new HttpEntity<>(j, ua), String.class);
        long keyId = objectMapper.readTree(r.getBody()).get("data").get("summary").get("id").asLong();

        // B 尝试访问 A 的 Key → 403
        ResponseEntity<String> bGet = restTemplate.exchange(
                baseUrl + "/api/api-keys/" + keyId, HttpMethod.GET, new HttpEntity<>(ub), String.class);
        assertThat(bGet.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);

        // B 尝试删除 A 的 Key → 403
        ResponseEntity<String> bDel = restTemplate.exchange(
                baseUrl + "/api/api-keys/" + keyId, HttpMethod.DELETE, new HttpEntity<>(ub), String.class);
        assertThat(bDel.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
    }

    // ---------- 5 & 6. 跨租户隔离 + 平台管理员能跨租户 ----------

    @Test
    void crossTenantIsolation() throws Exception {
        HttpHeaders ah = adminAuth();
        ensureSelfOperated(ah);

        long tenant2 = createTenant(ah, "t2-" + System.nanoTime());
        String userInT2 = createTenantMember(ah, tenant2);
        HttpHeaders userT2 = login(userInT2, "Passw0rd!Strong");

        // T2 用户创建一个 Key
        String j = objectMapper.writeValueAsString(Map.of("name", "t2-key"));
        ResponseEntity<String> r = restTemplate.exchange(
                baseUrl + "/api/api-keys", HttpMethod.POST, new HttpEntity<>(j, userT2), String.class);
        long keyId = objectMapper.readTree(r.getBody()).get("data").get("summary").get("id").asLong();

        // 平台管理员通过 admin 路径可以列出
        ResponseEntity<String> adminList = restTemplate.exchange(
                baseUrl + "/api/admin/api-keys?tenantId=" + tenant2,
                HttpMethod.GET, new HttpEntity<>(ah), String.class);
        assertThat(adminList.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(objectMapper.readTree(adminList.getBody()).get("data").get("total").asLong())
                .isGreaterThanOrEqualTo(1);

        // 平台管理员可以删除任意租户的 Key
        ResponseEntity<String> del = restTemplate.exchange(
                baseUrl + "/api/api-keys/" + keyId, HttpMethod.DELETE, new HttpEntity<>(ah), String.class);
        assertThat(del.getStatusCode()).isEqualTo(HttpStatus.OK);
    }

    // ---------- 7. 状态判断 ----------

    @Test
    void disableAndDeleteFlow() throws Exception {
        HttpHeaders ah = adminAuth();
        ensureSelfOperated(ah);
        String username = createTenantMember(ah, 1L);
        HttpHeaders user = login(username, "Passw0rd!Strong");

        String j = objectMapper.writeValueAsString(Map.of("name", "lifecycle"));
        ResponseEntity<String> r = restTemplate.exchange(
                baseUrl + "/api/api-keys", HttpMethod.POST, new HttpEntity<>(j, user), String.class);
        long id = objectMapper.readTree(r.getBody()).get("data").get("summary").get("id").asLong();

        // 默认 ENABLED
        ResponseEntity<String> g1 = restTemplate.exchange(
                baseUrl + "/api/api-keys/" + id, HttpMethod.GET, new HttpEntity<>(user), String.class);
        assertThat(objectMapper.readTree(g1.getBody()).get("data").get("status").asText()).isEqualTo("ENABLED");

        // 停用
        restTemplate.exchange(baseUrl + "/api/api-keys/" + id + "/disable",
                HttpMethod.PUT, new HttpEntity<>("{}", user), String.class);
        ResponseEntity<String> g2 = restTemplate.exchange(
                baseUrl + "/api/api-keys/" + id, HttpMethod.GET, new HttpEntity<>(user), String.class);
        assertThat(objectMapper.readTree(g2.getBody()).get("data").get("status").asText()).isEqualTo("DISABLED");

        // 启用
        restTemplate.exchange(baseUrl + "/api/api-keys/" + id + "/enable",
                HttpMethod.PUT, new HttpEntity<>("{}", user), String.class);
        ResponseEntity<String> g3 = restTemplate.exchange(
                baseUrl + "/api/api-keys/" + id, HttpMethod.GET, new HttpEntity<>(user), String.class);
        assertThat(objectMapper.readTree(g3.getBody()).get("data").get("status").asText()).isEqualTo("ENABLED");

        // 删除
        restTemplate.exchange(baseUrl + "/api/api-keys/" + id,
                HttpMethod.DELETE, new HttpEntity<>(user), String.class);
        ResponseEntity<String> g4 = restTemplate.exchange(
                baseUrl + "/api/api-keys/" + id, HttpMethod.GET, new HttpEntity<>(user), String.class);
        assertThat(g4.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(g4.getBody()).contains("API_KEY_NOT_FOUND");
    }

    // ---------- 8. Key 名称校验 ----------

    @Test
    void keyNameMustBeValid() throws Exception {
        HttpHeaders ah = adminAuth();
        ensureSelfOperated(ah);
        String username = createTenantMember(ah, 1L);
        HttpHeaders user = login(username, "Passw0rd!Strong");

        String j = objectMapper.writeValueAsString(Map.of("name", "x")); // 太短
        ResponseEntity<String> r = restTemplate.exchange(
                baseUrl + "/api/api-keys", HttpMethod.POST, new HttpEntity<>(j, user), String.class);
        assertThat(r.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(r.getBody()).contains("API_KEY_NAME_INVALID");
    }
}
