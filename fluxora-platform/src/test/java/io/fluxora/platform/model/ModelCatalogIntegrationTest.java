package io.fluxora.platform.model;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
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

/** 模型目录最小闭环：接口创建前先验证候选模型查询不会被既有上游接口误处理。 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Testcontainers
class ModelCatalogIntegrationTest {
    @Container static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:17-alpine")
            .withDatabaseName("fluxora").withUsername("fluxora").withPassword("fluxora");
    @DynamicPropertySource static void database(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", postgres::getJdbcUrl);
        registry.add("spring.datasource.username", postgres::getUsername);
        registry.add("spring.datasource.password", postgres::getPassword);
    }
    @LocalServerPort private int port;
    private final RestTemplate http = new RestTemplate();
    private final ObjectMapper json = new ObjectMapper();
    private String base;
    @Autowired private JdbcTemplate jdbc;
    @Autowired private ModelCatalogMapper mapper;

    ModelCatalogIntegrationTest() {
        http.setErrorHandler(new DefaultResponseErrorHandler() {
            @Override public boolean hasError(HttpStatusCode status) { return false; }
        });
    }
    @BeforeEach void prepare() { base = "http://localhost:" + port; }

    @Test void modelCatalogEndpointsAreAvailableToPlatformAdministrator() throws Exception {
        HttpHeaders headers = new HttpHeaders(); headers.set("Content-Type", "application/json");
        ResponseEntity<String> login = http.exchange(base + "/api/auth/login", HttpMethod.POST,
                new HttpEntity<>(json.writeValueAsString(Map.of("username", "admin", "password", "Admin@2026!")), headers), String.class);
        assertThat(login.getStatusCode()).isEqualTo(HttpStatus.OK);
        headers.put(HttpHeaders.COOKIE, login.getHeaders().get(HttpHeaders.SET_COOKIE));

        ResponseEntity<String> catalog = http.exchange(base + "/api/platform-models?page=1&size=20", HttpMethod.GET,
                new HttpEntity<>(headers), String.class);
        assertThat(catalog.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(catalog.getBody()).contains("items");
    }

    @Test void platformPriceCreatesImmutableHistoryAndKeepsSingleCurrentVersion() throws Exception {
        HttpHeaders headers = platformHeaders();
        String code = "price-" + System.nanoTime();
        String modelBody = json.writeValueAsString(Map.of("code", code, "displayName", "价格测试", "supportsCache", false));
        ResponseEntity<String> created = http.exchange(base + "/api/platform-models", HttpMethod.POST,
                new HttpEntity<>(modelBody, headers), String.class);
        long modelId = json.readTree(created.getBody()).path("data").path("id").asLong();

        for (String input : java.util.List.of("1.00000001", "2.00000002")) {
            String priceBody = json.writeValueAsString(Map.of("inputPrice", input, "outputPrice", "3.00000003"));
            ResponseEntity<String> price = http.exchange(base + "/api/platform-models/" + modelId + "/prices",
                    HttpMethod.POST, new HttpEntity<>(priceBody, headers), String.class);
            assertThat(price.getStatusCode()).isEqualTo(HttpStatus.OK);
        }
        ResponseEntity<String> history = http.exchange(base + "/api/platform-models/" + modelId + "/prices",
                HttpMethod.GET, new HttpEntity<>(headers), String.class);
        assertThat(json.readTree(history.getBody()).path("data").size()).isEqualTo(2);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM platform_model_price WHERE platform_model_id=? AND expires_at IS NULL", Long.class, modelId)).isEqualTo(1L);
    }

    @Test void routeTargetValidationRejectsCrossTenantProtocolAndCandidateMismatch() {
        long tenantOne = jdbc.queryForObject("INSERT INTO tenant(tenant_code,name,type,enabled) VALUES (?,?, 'STANDARD',TRUE) RETURNING id", Long.class, "route-own-"+System.nanoTime(), "路由本租户");
        long tenantTwo = jdbc.queryForObject("INSERT INTO tenant(tenant_code,name,type,enabled) VALUES (?,?, 'STANDARD',TRUE) RETURNING id", Long.class, "route-"+System.nanoTime(), "路由隔离租户");
        long provider = jdbc.queryForObject("INSERT INTO provider(name,code,scope_type) VALUES (?,?, 'PLATFORM_SHARED') RETURNING id", Long.class, "路由厂商", "route-provider-"+System.nanoTime());
        long openai = jdbc.queryForObject("INSERT INTO provider_base_url(provider_id,protocol,original_base_url,normalized_base_url) VALUES (?,'OPENAI','https://route.example/v1','https://route.example/v1') RETURNING id", Long.class, provider);
        long anthropic = jdbc.queryForObject("INSERT INTO provider_base_url(provider_id,protocol,original_base_url,normalized_base_url) VALUES (?,'ANTHROPIC','https://route.example/anthropic','https://route.example/anthropic') RETURNING id", Long.class, provider);
        long ownChannel = channel(tenantOne, openai, "本租户 OpenAI");
        long otherChannel = channel(tenantTwo, openai, "其他租户 OpenAI");
        long wrongProtocolChannel = channel(tenantOne, anthropic, "本租户 Anthropic");
        long ownCandidate = candidate(tenantOne, ownChannel, "own-model");
        long otherCandidate = candidate(tenantTwo, otherChannel, "other-model");
        long wrongProtocolCandidate = candidate(tenantOne, wrongProtocolChannel, "anthropic-model");
        long platformModel = jdbc.queryForObject("INSERT INTO platform_model(code,display_name) VALUES (?,?) RETURNING id", Long.class, "route-model-"+System.nanoTime(), "路由模型");
        long tenantModel = jdbc.queryForObject("INSERT INTO tenant_model(tenant_id,platform_model_id) VALUES (?,?) RETURNING id", Long.class, tenantOne, platformModel);
        long route = jdbc.queryForObject("INSERT INTO model_route(tenant_model_id,inbound_protocol) VALUES (?,'OPENAI') RETURNING id", Long.class, tenantModel);

        assertThat(mapper.validRouteTarget(route, ownChannel, ownCandidate)).isTrue();
        assertThat(mapper.validRouteTarget(route, otherChannel, otherCandidate)).isFalse();
        assertThat(mapper.validRouteTarget(route, wrongProtocolChannel, wrongProtocolCandidate)).isFalse();
        assertThat(mapper.validRouteTarget(route, ownChannel, otherCandidate)).isFalse();
    }

    private HttpHeaders platformHeaders() throws Exception {
        HttpHeaders headers = new HttpHeaders(); headers.set("Content-Type", "application/json");
        ResponseEntity<String> login = http.exchange(base + "/api/auth/login", HttpMethod.POST,
                new HttpEntity<>(json.writeValueAsString(Map.of("username", "admin", "password", "Admin@2026!")), headers), String.class);
        headers.put(HttpHeaders.COOKIE, login.getHeaders().get(HttpHeaders.SET_COOKIE));
        return headers;
    }
    private long channel(long tenantId,long baseUrlId,String name){return jdbc.queryForObject("INSERT INTO provider_channel(tenant_id,provider_base_url_id,name) VALUES (?,?,?) RETURNING id",Long.class,tenantId,baseUrlId,name);}
    private long candidate(long tenantId,long channelId,String id){return jdbc.queryForObject("INSERT INTO provider_channel_model(tenant_id,provider_channel_id,upstream_model_id,display_name,source_type) VALUES (?,?,?,?,'MANUAL') RETURNING id",Long.class,tenantId,channelId,id,id);}
}
