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
            @Override public boolean hasError(HttpStatusCode s) { return false; }
        });
    }

    @BeforeEach void setUp() { baseUrl = "http://localhost:" + port; }

    private HttpHeaders adminAuthHeaders() throws Exception {
        String json = objectMapper.writeValueAsString(Map.of("username", "admin", "password", "Admin@2026!"));
        HttpHeaders headers = new HttpHeaders();
        headers.set("Content-Type", "application/json");
        ResponseEntity<String> resp = restTemplate.exchange(
                baseUrl + "/api/auth/login", HttpMethod.POST, new HttpEntity<>(json, headers), String.class);
        HttpHeaders h = new HttpHeaders();
        h.put(HttpHeaders.COOKIE, resp.getHeaders().get(HttpHeaders.SET_COOKIE));
        h.set("Content-Type", "application/json");
        return h;
    }

    @Test void shouldCreateDefaultTenantAndTenantAdmin() throws Exception {
        HttpHeaders ah = adminAuthHeaders();
        ResponseEntity<String> sr = restTemplate.exchange(
                baseUrl + "/api/tenant/self-operated/status", HttpMethod.GET, new HttpEntity<>(ah), String.class);
        boolean init = objectMapper.readTree(sr.getBody()).get("data").get("initialized").asBoolean();

        if (!init) {
            String json = objectMapper.writeValueAsString(Map.of(
                    "tenantName", "Fluxora自营", "adminUsername", "soadmin_" + System.nanoTime(),
                    "adminPassword", "pass123", "adminDisplayName", "自营管理员"));
            ResponseEntity<String> ir = restTemplate.exchange(
                    baseUrl + "/api/tenant/self-operated/initialize", HttpMethod.POST, new HttpEntity<>(json, ah), String.class);
            assertThat(ir.getStatusCode()).isEqualTo(HttpStatus.OK);
            assertThat(objectMapper.readTree(ir.getBody()).get("data").get("tenantCode").asText()).isEqualTo("default");
        }
    }

    @Test void shouldRejectDuplicateInitialization() throws Exception {
        HttpHeaders ah = adminAuthHeaders();
        String json = objectMapper.writeValueAsString(Map.of(
                "tenantName", "test", "adminUsername", "dup_" + System.nanoTime(),
                "adminPassword", "pass123", "adminDisplayName", "admin"));
        ResponseEntity<String> first = restTemplate.exchange(
                baseUrl + "/api/tenant/self-operated/initialize", HttpMethod.POST, new HttpEntity<>(json, ah), String.class);
        if (first.getStatusCode() == HttpStatus.OK) {
            ResponseEntity<String> second = restTemplate.exchange(
                    baseUrl + "/api/tenant/self-operated/initialize", HttpMethod.POST, new HttpEntity<>(json, ah), String.class);
            assertThat(second.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        }
    }

    @Test void shouldNotAllowDeleteSelfOperated() throws Exception {
        HttpHeaders ah = adminAuthHeaders();
        String json = objectMapper.writeValueAsString(Map.of(
                "tenantName", "test", "adminUsername", "prot_" + System.nanoTime(),
                "adminPassword", "pass123", "adminDisplayName", "admin"));
        ResponseEntity<String> ir = restTemplate.exchange(
                baseUrl + "/api/tenant/self-operated/initialize", HttpMethod.POST, new HttpEntity<>(json, ah), String.class);
        if (ir.getStatusCode() == HttpStatus.OK) {
            long tid = objectMapper.readTree(ir.getBody()).get("data").get("tenantId").asLong();
            ResponseEntity<String> dr = restTemplate.exchange(
                    baseUrl + "/api/tenant/" + tid, HttpMethod.DELETE, new HttpEntity<>(ah), String.class);
            assertThat(dr.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
            assertThat(dr.getBody()).contains("自营租户受保护");
        }
    }
}
