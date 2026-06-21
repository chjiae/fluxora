package io.fluxora.platform.tenant;

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

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Testcontainers
class TenantManagementIntegrationTest {

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

    public TenantManagementIntegrationTest() {
        this.restTemplate = new RestTemplate();
        this.restTemplate.setErrorHandler(new DefaultResponseErrorHandler() {
            @Override public boolean hasError(HttpStatusCode s) { return false; }
        });
    }

    @BeforeEach void setUp() { baseUrl = "http://localhost:" + port; }

    private HttpHeaders adminAuth() throws Exception {
        String json = objectMapper.writeValueAsString(Map.of("username", "admin", "password", "Admin@2026!"));
        HttpHeaders h = new HttpHeaders(); h.set("Content-Type", "application/json");
        ResponseEntity<String> r = restTemplate.exchange(
                baseUrl + "/api/auth/login", HttpMethod.POST, new HttpEntity<>(json, h), String.class);
        HttpHeaders ah = new HttpHeaders();
        ah.put(HttpHeaders.COOKIE, r.getHeaders().get(HttpHeaders.SET_COOKIE));
        ah.set("Content-Type", "application/json");
        return ah;
    }

    @Test void shouldListTenants() throws Exception {
        ResponseEntity<String> r = restTemplate.exchange(
                baseUrl + "/api/tenant?page=1&size=10", HttpMethod.GET, new HttpEntity<>(adminAuth()), String.class);
        assertThat(r.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(objectMapper.readTree(r.getBody()).get("success").asBoolean()).isTrue();
    }

    @Test void shouldCreateUpdateEnableDisableExpireDeleteTenant() throws Exception {
        HttpHeaders ah = adminAuth();
        String code = "tm-" + System.nanoTime();

        // CREATE
        String cj = objectMapper.writeValueAsString(Map.of("tenantCode", code, "name", "测试", "type", "STANDARD", "enabled", true));
        ResponseEntity<String> cr = restTemplate.exchange(baseUrl + "/api/tenant", HttpMethod.POST, new HttpEntity<>(cj, ah), String.class);
        assertThat(cr.getStatusCode()).isEqualTo(HttpStatus.OK);
        long tid = objectMapper.readTree(cr.getBody()).get("data").get("id").asLong();

        // UPDATE
        String uj = objectMapper.writeValueAsString(Map.of("name", "已更新", "description", "desc"));
        ResponseEntity<String> ur = restTemplate.exchange(baseUrl + "/api/tenant/" + tid, HttpMethod.PUT, new HttpEntity<>(uj, ah), String.class);
        assertThat(ur.getStatusCode()).isEqualTo(HttpStatus.OK);

        // DISABLE
        ResponseEntity<String> disr = restTemplate.exchange(baseUrl + "/api/tenant/" + tid + "/disable", HttpMethod.PUT, new HttpEntity<>("{}", ah), String.class);
        assertThat(disr.getStatusCode()).isEqualTo(HttpStatus.OK);

        // ENABLE
        ResponseEntity<String> enr = restTemplate.exchange(baseUrl + "/api/tenant/" + tid + "/enable", HttpMethod.PUT, new HttpEntity<>("{}", ah), String.class);
        assertThat(enr.getStatusCode()).isEqualTo(HttpStatus.OK);

        // SET EXPIRE
        String ej = objectMapper.writeValueAsString(Map.of("expireAt", "2099-12-31T23:59:59Z"));
        ResponseEntity<String> expr = restTemplate.exchange(baseUrl + "/api/tenant/" + tid + "/expire", HttpMethod.PUT, new HttpEntity<>(ej, ah), String.class);
        assertThat(expr.getStatusCode()).isEqualTo(HttpStatus.OK);

        // DELETE
        ResponseEntity<String> dr = restTemplate.exchange(baseUrl + "/api/tenant/" + tid, HttpMethod.DELETE, new HttpEntity<>(ah), String.class);
        assertThat(dr.getStatusCode()).isEqualTo(HttpStatus.OK);

        // Verify deleted
        ResponseEntity<String> lr = restTemplate.exchange(baseUrl + "/api/tenant?keyword=" + code, HttpMethod.GET, new HttpEntity<>(adminAuth()), String.class);
        assertThat(objectMapper.readTree(lr.getBody()).get("data").get("items").size()).isEqualTo(0);
    }

    @Test void shouldRejectDuplicateTenantCode() throws Exception {
        HttpHeaders ah = adminAuth();
        String code = "dup-" + System.nanoTime();
        String j = objectMapper.writeValueAsString(Map.of("tenantCode", code, "name", "dup", "type", "STANDARD", "enabled", true));
        restTemplate.exchange(baseUrl + "/api/tenant", HttpMethod.POST, new HttpEntity<>(j, ah), String.class);
        ResponseEntity<String> r = restTemplate.exchange(baseUrl + "/api/tenant", HttpMethod.POST, new HttpEntity<>(j, ah), String.class);
        assertThat(r.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(r.getBody()).contains("租户码已被使用");
    }

    @Test void shouldRejectTenantAdminFromManagingPlatformTenants() throws Exception {
        // Init self-op with tenant admin
        HttpHeaders ah = adminAuth();
        String adminUser = "ta_" + System.nanoTime();
        String ij = objectMapper.writeValueAsString(Map.of(
                "tenantName", "test", "adminUsername", adminUser,
                "adminPassword", "pass123", "adminDisplayName", "TA"));
        restTemplate.exchange(baseUrl + "/api/tenant/self-operated/initialize", HttpMethod.POST, new HttpEntity<>(ij, ah), String.class);

        // Login as tenant admin
        String lj = objectMapper.writeValueAsString(Map.of("username", adminUser, "password", "pass123"));
        HttpHeaders lh = new HttpHeaders(); lh.set("Content-Type", "application/json");
        ResponseEntity<String> lr = restTemplate.exchange(baseUrl + "/api/auth/login", HttpMethod.POST, new HttpEntity<>(lj, lh), String.class);
        HttpHeaders th = new HttpHeaders();
        th.put(HttpHeaders.COOKIE, lr.getHeaders().get(HttpHeaders.SET_COOKIE));

        // Try accessing tenant management (should be 403)
        ResponseEntity<String> r = restTemplate.exchange(baseUrl + "/api/tenant", HttpMethod.GET, new HttpEntity<>(th), String.class);
        assertThat(r.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
    }

    @Test void shouldNotAllowCreateSelfOperatedViaApi() throws Exception {
        HttpHeaders ah = adminAuth();
        String j = objectMapper.writeValueAsString(Map.of("tenantCode", "notdefault", "name", "bad", "type", "SELF_OPERATED", "enabled", true));
        ResponseEntity<String> r = restTemplate.exchange(baseUrl + "/api/tenant", HttpMethod.POST, new HttpEntity<>(j, ah), String.class);
        assertThat(r.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
    }
}
