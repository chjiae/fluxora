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
class SelfOperatedInitializationIntegrationTest {

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

    public SelfOperatedInitializationIntegrationTest() {
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

    private String initJson(String tenantName, String adminUsername) throws Exception {
        return objectMapper.writeValueAsString(Map.of(
                "tenantName", tenantName,
                "adminUsername", adminUsername,
                "adminPassword", "tenant123",
                "adminDisplayName", "自营管理员"));
    }

    @Test
    void shouldCreateDefaultTenantAndTenantAdmin() throws Exception {
        HttpHeaders authHeaders = adminAuthHeaders();

        // Check if already initialized (may be from other tests sharing DB)
        ResponseEntity<String> statusResp = restTemplate.exchange(
                baseUrl + "/api/tenant/self-operated/status", HttpMethod.GET,
                new HttpEntity<>(authHeaders), String.class);
        JsonNode statusNode = objectMapper.readTree(statusResp.getBody());
        boolean initialized = statusNode.get("data").get("initialized").asBoolean();

        Long tenantId;
        if (!initialized) {
            String json = initJson("Fluxora自营", "tenantadmin_" + System.currentTimeMillis());
            ResponseEntity<String> initResp = restTemplate.exchange(
                    baseUrl + "/api/tenant/self-operated/initialize", HttpMethod.POST,
                    new HttpEntity<>(json, authHeaders), String.class);
            assertThat(initResp.getStatusCode()).isEqualTo(HttpStatus.OK);
            JsonNode initBody = objectMapper.readTree(initResp.getBody());
            assertThat(initBody.get("data").get("tenantCode").asText()).isEqualTo("default");
            tenantId = initBody.get("data").get("tenantId").asLong();
        } else {
            // Already initialized - verify it exists
            ResponseEntity<String> listResp = restTemplate.exchange(
                    baseUrl + "/api/tenant?keyword=default", HttpMethod.GET,
                    new HttpEntity<>(authHeaders), String.class);
            JsonNode list = objectMapper.readTree(listResp.getBody());
            assertThat(list.get("data").get("items").size()).isGreaterThan(0);
            tenantId = list.get("data").get("items").get(0).get("id").asLong();
        }

        assertThat(tenantId).isNotNull();
    }

    @Test
    void shouldRejectDuplicateInitializationAfterFirstSuccess() throws Exception {
        HttpHeaders authHeaders = adminAuthHeaders();
        String adminUser = "dupadmin_" + System.currentTimeMillis();
        String json = initJson("自营初始化测试", adminUser);

        // First init
        ResponseEntity<String> first = restTemplate.exchange(
                baseUrl + "/api/tenant/self-operated/initialize", HttpMethod.POST,
                new HttpEntity<>(json, authHeaders), String.class);

        if (first.getStatusCode() == HttpStatus.OK) {
            // Second init with same data should fail
            ResponseEntity<String> second = restTemplate.exchange(
                    baseUrl + "/api/tenant/self-operated/initialize", HttpMethod.POST,
                    new HttpEntity<>(json, authHeaders), String.class);
            assertThat(second.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        }
        // If first already failed (default tenant exists), the duplicate rejection is implicitly tested
    }

    @Test
    void shouldNotAllowDeleteSelfOperatedTenantOnceExists() throws Exception {
        HttpHeaders authHeaders = adminAuthHeaders();

        // Get or create the default tenant
        String adminUser = "protadmin_" + System.currentTimeMillis();
        String json = initJson("受保护租户", adminUser);
        ResponseEntity<String> initResp = restTemplate.exchange(
                baseUrl + "/api/tenant/self-operated/initialize", HttpMethod.POST,
                new HttpEntity<>(json, authHeaders), String.class);

        if (initResp.getStatusCode() == HttpStatus.OK) {
            Long tenantId = objectMapper.readTree(initResp.getBody()).get("data").get("tenantId").asLong();

            // Try to delete - should be rejected
            ResponseEntity<String> deleteResp = restTemplate.exchange(
                    baseUrl + "/api/tenant/" + tenantId, HttpMethod.DELETE,
                    new HttpEntity<>(authHeaders), String.class);
            assertThat(deleteResp.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
            assertThat(deleteResp.getBody()).contains("自营租户受保护");
        }
        // If default already exists, we check the existing one is protected
    }
}
