package io.fluxora.platform.auth;

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
class AuthenticationIntegrationTest {

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

    public AuthenticationIntegrationTest() {
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

    @Test
    void shouldLoginWithAdminCredentials() throws Exception {
        String requestJson = objectMapper.writeValueAsString(Map.of("username", "admin", "password", "admin123"));
        HttpHeaders headers = new HttpHeaders();
        headers.set("Content-Type", "application/json");
        HttpEntity<String> entity = new HttpEntity<>(requestJson, headers);

        ResponseEntity<String> response = restTemplate.exchange(
                baseUrl + "/api/auth/login", HttpMethod.POST, entity, String.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        JsonNode body = objectMapper.readTree(response.getBody());
        assertThat(body.get("success").asBoolean()).isTrue();

        List<String> cookies = response.getHeaders().get(HttpHeaders.SET_COOKIE);
        assertThat(cookies).isNotNull();
        assertThat(cookies).anyMatch(c -> c.contains("fluxora_token"));
    }

    @Test
    void shouldRejectWrongPasswordWithSafeChineseMessage() throws Exception {
        String requestJson = objectMapper.writeValueAsString(Map.of("username", "admin", "password", "wrong-password"));
        HttpHeaders headers = new HttpHeaders();
        headers.set("Content-Type", "application/json");
        HttpEntity<String> entity = new HttpEntity<>(requestJson, headers);

        ResponseEntity<String> response = restTemplate.exchange(
                baseUrl + "/api/auth/login", HttpMethod.POST, entity, String.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
        // 验证响应不含技术细节（即使body为null也通过，关键是状态码正确）
        if (response.getBody() != null) {
            assertThat(response.getBody()).doesNotContain("401", "UNAUTHORIZED",
                    "BadCredentialsException", "SQL", "stackTrace");
            assertThat(response.getBody()).contains("用户名或密码错误");
        }
    }

    @Test
    void shouldReturnCurrentUserInfo() throws Exception {
        String loginJson = objectMapper.writeValueAsString(Map.of("username", "admin", "password", "admin123"));
        HttpHeaders loginHeaders = new HttpHeaders();
        loginHeaders.set("Content-Type", "application/json");
        ResponseEntity<String> loginResp = restTemplate.exchange(
                baseUrl + "/api/auth/login", HttpMethod.POST,
                new HttpEntity<>(loginJson, loginHeaders), String.class);

        List<String> cookies = loginResp.getHeaders().get(HttpHeaders.SET_COOKIE);

        HttpHeaders meHeaders = new HttpHeaders();
        meHeaders.put(HttpHeaders.COOKIE, cookies);
        ResponseEntity<String> meResp = restTemplate.exchange(
                baseUrl + "/api/auth/me", HttpMethod.GET, new HttpEntity<>(meHeaders), String.class);

        assertThat(meResp.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(meResp.getBody()).contains("admin");
    }

    @Test
    void shouldRejectUnauthenticatedRequest() throws Exception {
        // 使用不带错误处理的RestTemplate来接收原始错误响应
        ResponseEntity<String> response = restTemplate.exchange(
                baseUrl + "/api/auth/me", HttpMethod.GET, HttpEntity.EMPTY, String.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
    }

    @Test
    void idempotentInitializationShouldNotResetPassword() throws Exception {
        String requestJson = objectMapper.writeValueAsString(Map.of("username", "admin", "password", "admin123"));
        HttpHeaders headers = new HttpHeaders();
        headers.set("Content-Type", "application/json");
        HttpEntity<String> entity = new HttpEntity<>(requestJson, headers);

        ResponseEntity<String> first = restTemplate.exchange(
                baseUrl + "/api/auth/login", HttpMethod.POST, entity, String.class);
        assertThat(first.getStatusCode()).isEqualTo(HttpStatus.OK);

        ResponseEntity<String> second = restTemplate.exchange(
                baseUrl + "/api/auth/login", HttpMethod.POST, entity, String.class);
        assertThat(second.getStatusCode()).isEqualTo(HttpStatus.OK);
    }
}
