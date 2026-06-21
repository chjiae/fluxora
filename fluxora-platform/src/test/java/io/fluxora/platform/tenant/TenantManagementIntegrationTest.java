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

import java.util.List;
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

    @LocalServerPort
    private int port;

    private final RestTemplate restTemplate;
    private final ObjectMapper objectMapper = new ObjectMapper();
    private String baseUrl;

    public TenantManagementIntegrationTest() {
        this.restTemplate = new RestTemplate();
        this.restTemplate.setErrorHandler(new DefaultResponseErrorHandler() {
            @Override
            public boolean hasError(HttpStatusCode statusCode) {
                return false;
            }
        });
    }

    @BeforeEach
    void setUp() {
        baseUrl = "http://localhost:" + port;
    }

    private HttpHeaders adminAuthHeaders() throws Exception {
        String json = objectMapper.writeValueAsString(Map.of("username", "admin", "password", "admin123"));
        HttpHeaders headers = new HttpHeaders();
        headers.set("Content-Type", "application/json");
        ResponseEntity<String> resp = restTemplate.exchange(
                baseUrl + "/api/auth/login", HttpMethod.POST, new HttpEntity<>(json, headers), String.class);
        HttpHeaders authHeaders = new HttpHeaders();
        authHeaders.put(HttpHeaders.COOKIE, resp.getHeaders().get(HttpHeaders.SET_COOKIE));
        authHeaders.set("Content-Type", "application/json");
        return authHeaders;
    }

    @Test
    void shouldListTenantsWithPagination() throws Exception {
        HttpHeaders headers = adminAuthHeaders();
        ResponseEntity<String> resp = restTemplate.exchange(
                baseUrl + "/api/tenant?page=1&size=10", HttpMethod.GET,
                new HttpEntity<>(headers), String.class);

        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.OK);
        JsonNode body = objectMapper.readTree(resp.getBody());
        assertThat(body.get("success").asBoolean()).isTrue();
        assertThat(body.get("data").get("items").isArray()).isTrue();
    }

    @Test
    void shouldCreateAndUpdateTenant() throws Exception {
        HttpHeaders headers = adminAuthHeaders();

        // Create
        String createJson = objectMapper.writeValueAsString(Map.of(
                "tenantCode", "test-tenant", "name", "测试租户", "type", "THIRD_PARTY", "enabled", true));
        ResponseEntity<String> createResp = restTemplate.exchange(
                baseUrl + "/api/tenant", HttpMethod.POST,
                new HttpEntity<>(createJson, headers), String.class);
        assertThat(createResp.getStatusCode()).isEqualTo(HttpStatus.OK);

        JsonNode created = objectMapper.readTree(createResp.getBody());
        Long tenantId = created.get("data").get("id").asLong();

        // Update
        String updateJson = objectMapper.writeValueAsString(Map.of(
                "name", "测试租户已更新", "enabled", true, "expireAt", "2099-12-31T23:59:59Z"));
        ResponseEntity<String> updateResp = restTemplate.exchange(
                baseUrl + "/api/tenant/" + tenantId, HttpMethod.PUT,
                new HttpEntity<>(updateJson, headers), String.class);
        assertThat(updateResp.getStatusCode()).isEqualTo(HttpStatus.OK);

        // Verify update
        ResponseEntity<String> detailResp = restTemplate.exchange(
                baseUrl + "/api/tenant/" + tenantId, HttpMethod.GET,
                new HttpEntity<>(headers), String.class);
        JsonNode detail = objectMapper.readTree(detailResp.getBody());
        assertThat(detail.get("data").get("name").asText()).isEqualTo("测试租户已更新");
    }

    @Test
    void shouldRejectDuplicateTenantCode() throws Exception {
        HttpHeaders headers = adminAuthHeaders();

        String json = objectMapper.writeValueAsString(Map.of(
                "tenantCode", "dup-tenant", "name", "重复租户", "type", "THIRD_PARTY", "enabled", true));
        // First create
        restTemplate.exchange(baseUrl + "/api/tenant", HttpMethod.POST,
                new HttpEntity<>(json, headers), String.class);
        // Second create with same code
        ResponseEntity<String> dupResp = restTemplate.exchange(
                baseUrl + "/api/tenant", HttpMethod.POST,
                new HttpEntity<>(json, headers), String.class);

        assertThat(dupResp.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(dupResp.getBody()).contains("租户码已被使用");
    }

    @Test
    void shouldSoftDeleteTenant() throws Exception {
        HttpHeaders headers = adminAuthHeaders();

        String json = objectMapper.writeValueAsString(Map.of(
                "tenantCode", "del-tenant", "name", "待删除租户", "type", "THIRD_PARTY", "enabled", true));
        ResponseEntity<String> createResp = restTemplate.exchange(
                baseUrl + "/api/tenant", HttpMethod.POST,
                new HttpEntity<>(json, headers), String.class);
        Long tenantId = objectMapper.readTree(createResp.getBody()).get("data").get("id").asLong();

        ResponseEntity<String> deleteResp = restTemplate.exchange(
                baseUrl + "/api/tenant/" + tenantId, HttpMethod.DELETE,
                new HttpEntity<>(headers), String.class);
        assertThat(deleteResp.getStatusCode()).isEqualTo(HttpStatus.OK);

        // Verify not in list
        ResponseEntity<String> listResp = restTemplate.exchange(
                baseUrl + "/api/tenant?keyword=del-tenant", HttpMethod.GET,
                new HttpEntity<>(headers), String.class);
        JsonNode list = objectMapper.readTree(listResp.getBody());
        assertThat(list.get("data").get("items").size()).isEqualTo(0);
    }

    @Test
    void shouldRejectTenantAdminAccessingTenantManagement() throws Exception {
        // Initialize self-operated first
        HttpHeaders adminHeaders = adminAuthHeaders();
        String initJson = objectMapper.writeValueAsString(Map.of(
                "tenantName", "Fluxora自营",
                "adminUsername", "ta4mgmt",
                "adminPassword", "tenant123",
                "adminDisplayName", "自营管理员"));
        restTemplate.exchange(baseUrl + "/api/tenant/self-operated/initialize", HttpMethod.POST,
                new HttpEntity<>(initJson, adminHeaders), String.class);

        // Login as tenant admin
        String taLoginJson = objectMapper.writeValueAsString(
                Map.of("username", "ta4mgmt", "password", "tenant123"));
        HttpHeaders taLoginHeaders = new HttpHeaders();
        taLoginHeaders.set("Content-Type", "application/json");
        ResponseEntity<String> taLoginResp = restTemplate.exchange(
                baseUrl + "/api/auth/login", HttpMethod.POST,
                new HttpEntity<>(taLoginJson, taLoginHeaders), String.class);

        HttpHeaders taHeaders = new HttpHeaders();
        taHeaders.put(HttpHeaders.COOKIE, taLoginResp.getHeaders().get(HttpHeaders.SET_COOKIE));

        // Try to access tenant management
        ResponseEntity<String> resp = restTemplate.exchange(
                baseUrl + "/api/tenant", HttpMethod.GET,
                new HttpEntity<>(taHeaders), String.class);

        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
    }
}
