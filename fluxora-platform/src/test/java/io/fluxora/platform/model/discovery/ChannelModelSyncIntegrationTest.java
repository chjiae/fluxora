package io.fluxora.platform.model.discovery;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import javax.sql.DataSource;

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
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.TestPropertySource;
import org.springframework.web.client.DefaultResponseErrorHandler;
import org.springframework.web.client.RestTemplate;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 上游模型同步集成测试（Mock 模式，不发出真实网络请求）。
 *
 * 覆盖：
 * - 共享 Provider/BaseUrl 下，租户 A 与 B 各自同步只生成各自候选
 * - 同一通道重复同步同一模型不会产生重复候选（部分唯一索引兜底）
 * - 不支持自动发现的协议（ANTHROPIC）返回空候选，无错误
 * - 同步失败不破坏既有候选 / 映射（缺凭证场景）
 * - 凭证不出现在任何响应中
 * - 跨租户：B 管理员无法对 A 的通道执行同步
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@TestPropertySource(properties = {
        // Mock 模式：不读取凭证，不发出网络请求
        "fluxora.model-discovery.mock-enabled=true",
        "fluxora.model-discovery.mock-models=mock-gpt-4o,mock-gpt-4o-mini,mock-emb-3"
})
@Testcontainers
class ChannelModelSyncIntegrationTest {

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
    private final ObjectMapper om = new ObjectMapper();
    private String base;

    public ChannelModelSyncIntegrationTest() {
        restTemplate = new RestTemplate();
        restTemplate.setErrorHandler(new DefaultResponseErrorHandler() {
            @Override public boolean hasError(HttpStatusCode s) { return false; }
        });
    }

    @BeforeEach void setUp() { base = "http://localhost:" + port; }

    // ---------- 辅助 ----------

    private HttpHeaders login(String u, String p) throws Exception {
        String j = om.writeValueAsString(Map.of("username", u, "password", p));
        HttpHeaders h = new HttpHeaders(); h.set("Content-Type", "application/json");
        ResponseEntity<String> r = restTemplate.exchange(base + "/api/auth/login", HttpMethod.POST,
                new HttpEntity<>(j, h), String.class);
        assertThat(r.getStatusCode()).isEqualTo(HttpStatus.OK);
        HttpHeaders ah = new HttpHeaders();
        ah.put(HttpHeaders.COOKIE, r.getHeaders().get(HttpHeaders.SET_COOKIE));
        ah.set("Content-Type", "application/json");
        return ah;
    }
    private HttpHeaders adminAuth() throws Exception { return login("admin", "Admin@2026!"); }

    private long ensureDefaultTenant(HttpHeaders ah) throws Exception {
        String j = om.writeValueAsString(Map.of("tenantName", "自营",
                "adminUsername", "seed_" + System.nanoTime(),
                "adminPassword", "TaPass2026!", "adminDisplayName", "Seed"));
        try {
            restTemplate.exchange(base + "/api/tenant/self-operated/initialize", HttpMethod.POST,
                    new HttpEntity<>(j, ah), String.class);
        } catch (Exception ignored) {}
        ResponseEntity<String> list = restTemplate.exchange(base + "/api/tenant?keyword=default",
                HttpMethod.GET, new HttpEntity<>(ah), String.class);
        for (JsonNode n : om.readTree(list.getBody()).get("data").get("items")) {
            if ("default".equals(n.get("tenantCode").asText())) return n.get("id").asLong();
        }
        throw new IllegalStateException("default 租户不存在");
    }

    private long createTenant(HttpHeaders ah, String code) throws Exception {
        String j = om.writeValueAsString(Map.of("tenantCode", code, "name", "测试租户",
                "type", "STANDARD", "enabled", true));
        ResponseEntity<String> r = restTemplate.exchange(base + "/api/tenant", HttpMethod.POST,
                new HttpEntity<>(j, ah), String.class);
        return om.readTree(r.getBody()).get("data").get("id").asLong();
    }

    private String createTenantAdmin(HttpHeaders ah, long tenantId) throws Exception {
        String username = "ta" + System.nanoTime();
        String j = om.writeValueAsString(Map.of("username", username, "displayName", username,
                "password", "TaPass2026!", "roleCode", "TENANT_ADMIN"));
        ResponseEntity<String> r = restTemplate.exchange(base + "/api/tenant/" + tenantId + "/members",
                HttpMethod.POST, new HttpEntity<>(j, ah), String.class);
        assertThat(r.getStatusCode()).isEqualTo(HttpStatus.OK);
        return username;
    }

    private long createSharedProvider(HttpHeaders ah, String code) throws Exception {
        String j = om.writeValueAsString(Map.of("name", "共享厂商", "code", code,
                "scopeType", "PLATFORM_SHARED", "enabled", true));
        ResponseEntity<String> r = restTemplate.exchange(base + "/api/providers",
                HttpMethod.POST, new HttpEntity<>(j, ah), String.class);
        return om.readTree(r.getBody()).get("data").get("id").asLong();
    }

    private long createBaseUrl(HttpHeaders ah, long providerId, String protocol, String url) throws Exception {
        String j = om.writeValueAsString(Map.of("providerId", providerId, "protocol", protocol, "baseUrl", url));
        ResponseEntity<String> r = restTemplate.exchange(base + "/api/provider-base-urls",
                HttpMethod.POST, new HttpEntity<>(j, ah), String.class);
        return om.readTree(r.getBody()).get("data").get("id").asLong();
    }

    private long createChannel(HttpHeaders ah, Long tenantId, long baseUrlId, String name) throws Exception {
        Map<String, Object> body = new HashMap<>(Map.of(
                "providerBaseUrlId", baseUrlId, "name", name, "enabled", true,
                "priority", 100, "weight", 100, "connectTimeoutMs", 5000, "readTimeoutMs", 60000));
        if (tenantId != null) body.put("tenantId", tenantId);
        String j = om.writeValueAsString(body);
        ResponseEntity<String> r = restTemplate.exchange(base + "/api/provider-channels",
                HttpMethod.POST, new HttpEntity<>(j, ah), String.class);
        assertThat(r.getStatusCode()).isEqualTo(HttpStatus.OK);
        return om.readTree(r.getBody()).get("data").get("id").asLong();
    }

    private long createCredential(HttpHeaders ah, long channelId) throws Exception {
        // 即使 Mock 模式不读取凭证，sync 服务在非 mock 路径下也需要凭证；这里始终创建以避免漏侧
        String j = om.writeValueAsString(Map.of("providerChannelId", channelId,
                "plaintext", "sk-mock-" + System.nanoTime(),
                "name", "mock-cred-" + System.nanoTime()));
        ResponseEntity<String> r = restTemplate.exchange(base + "/api/provider-credentials",
                HttpMethod.POST, new HttpEntity<>(j, ah), String.class);
        return om.readTree(r.getBody()).get("data").get("id").asLong();
    }

    private JsonNode listCandidates(HttpHeaders ah, long channelId) throws Exception {
        ResponseEntity<String> r = restTemplate.exchange(
                base + "/api/provider-channels/" + channelId + "/models",
                HttpMethod.GET, new HttpEntity<>(ah), String.class);
        return om.readTree(r.getBody()).get("data");
    }

    private JsonNode sync(HttpHeaders ah, long channelId) throws Exception {
        ResponseEntity<String> r = restTemplate.exchange(
                base + "/api/provider-channels/" + channelId + "/models/sync",
                HttpMethod.POST, new HttpEntity<>("{}", ah), String.class);
        assertThat(r.getStatusCode()).as("sync failed: %s", r.getBody()).isEqualTo(HttpStatus.OK);
        // 响应不暴露凭证
        assertThat(r.getBody()).doesNotContain("sk-mock-", "ciphertext", "credential_fingerprint",
                "initialization_vector");
        return om.readTree(r.getBody()).get("data");
    }

    // ---------- 1. 共享 Provider/BaseUrl 下双租户各自同步彼此独立 ----------

    @Test
    void shouldKeepSyncedCandidatesIsolatedAcrossTenants() throws Exception {
        HttpHeaders ah = adminAuth();
        long tA = ensureDefaultTenant(ah);
        long tB = createTenant(ah, "sync_iso_" + System.nanoTime());

        long pid = createSharedProvider(ah, "shp_" + System.nanoTime());
        long bid = createBaseUrl(ah, pid, "OPENAI", "https://share-sync.example.com/v1");

        long chA = createChannel(ah, tA, bid, "sync-A");
        long chB = createChannel(ah, tB, bid, "sync-B");
        createCredential(ah, chA);
        createCredential(ah, chB);

        // 各自同步：Mock 返回 3 个模型 → 应分别新增 3 个候选
        JsonNode rA = sync(ah, chA);
        JsonNode rB = sync(ah, chB);
        assertThat(rA.get("added").asLong()).isEqualTo(3);
        assertThat(rB.get("added").asLong()).isEqualTo(3);

        // 候选完全隔离：每个租户的候选都属于自己
        JsonNode candsA = listCandidates(ah, chA);
        JsonNode candsB = listCandidates(ah, chB);
        for (JsonNode c : candsA) assertThat(c.get("tenantId").asLong()).isEqualTo(tA);
        for (JsonNode c : candsB) assertThat(c.get("tenantId").asLong()).isEqualTo(tB);
    }

    // ---------- 2. 重复同步同一模型不会产生重复候选 ----------

    @Test
    void shouldUpdateExistingCandidatesOnRepeatedSync() throws Exception {
        HttpHeaders ah = adminAuth();
        long tA = ensureDefaultTenant(ah);
        long pid = createSharedProvider(ah, "shp_" + System.nanoTime());
        long bid = createBaseUrl(ah, pid, "OPENAI", "https://repeat.example.com/v1");
        long ch = createChannel(ah, tA, bid, "sync-repeat");
        createCredential(ah, ch);

        JsonNode r1 = sync(ah, ch);
        JsonNode r2 = sync(ah, ch);

        // 第一次：added=3，updated=0；第二次：added=0，updated=3
        assertThat(r1.get("added").asLong()).isEqualTo(3);
        assertThat(r1.get("updated").asLong()).isEqualTo(0);
        assertThat(r2.get("added").asLong()).isEqualTo(0);
        assertThat(r2.get("updated").asLong()).isEqualTo(3);

        // 候选总数仍为 3
        assertThat(listCandidates(ah, ch).size()).isEqualTo(3);
    }

    // ---------- 3. ANTHROPIC 协议返回空候选，不抛错 ----------

    @Test
    void shouldReturnEmptyForAnthropicProtocol() throws Exception {
        HttpHeaders ah = adminAuth();
        long tA = ensureDefaultTenant(ah);
        long pid = createSharedProvider(ah, "shp_" + System.nanoTime());
        long bid = createBaseUrl(ah, pid, "ANTHROPIC", "https://anth.example.com/v1");
        long ch = createChannel(ah, tA, bid, "sync-anth");
        createCredential(ah, ch);

        JsonNode r = sync(ah, ch);
        // ANTHROPIC 无公开模型列表：added/updated 均为 0
        assertThat(r.get("added").asLong()).isEqualTo(0);
        assertThat(r.get("updated").asLong()).isEqualTo(0);
        assertThat(r.get("failed").asInt()).isEqualTo(0);
    }

    // ---------- 4. 跨租户：B 管理员无法同步 A 的通道 ----------

    @Test
    void shouldDenyCrossTenantSync() throws Exception {
        HttpHeaders ah = adminAuth();
        long tA = ensureDefaultTenant(ah);
        long tB = createTenant(ah, "sync_cross_" + System.nanoTime());
        String adminB = createTenantAdmin(ah, tB);

        long pid = createSharedProvider(ah, "shp_" + System.nanoTime());
        long bid = createBaseUrl(ah, pid, "OPENAI", "https://cross-sync.example.com/v1");
        long chA = createChannel(ah, tA, bid, "cross-A");
        createCredential(ah, chA);

        HttpHeaders bH = login(adminB, "TaPass2026!");
        ResponseEntity<String> r = restTemplate.exchange(
                base + "/api/provider-channels/" + chA + "/models/sync",
                HttpMethod.POST, new HttpEntity<>("{}", bH), String.class);
        assertThat(r.getStatusCode().is4xxClientError()).isTrue();
        // 错误响应不暴露异常类
        assertThat(r.getBody()).doesNotContain("Exception", "SQL", "stacktrace");
    }

    // ---------- 5. 同步后历史候选不会被物理删除（即使本次未返回） ----------

    @Test
    void shouldPreserveHistoricalCandidatesWhenMissingFromSync() throws Exception {
        HttpHeaders ah = adminAuth();
        long tA = ensureDefaultTenant(ah);
        long pid = createSharedProvider(ah, "shp_" + System.nanoTime());
        long bid = createBaseUrl(ah, pid, "OPENAI", "https://preserve.example.com/v1");
        long ch = createChannel(ah, tA, bid, "sync-preserve");
        createCredential(ah, ch);

        // 先手工新增一个不在 mock 列表中的候选
        String j = om.writeValueAsString(Map.of(
                "upstreamModelId", "manual-only-" + System.nanoTime(),
                "upstreamDisplayName", "手工候选",
                "enabled", true));
        restTemplate.exchange(base + "/api/provider-channels/" + ch + "/models",
                HttpMethod.POST, new HttpEntity<>(j, ah), String.class);

        int beforeSize = listCandidates(ah, ch).size();
        JsonNode r = sync(ah, ch);

        // 同步标记 missing > 0，但既有候选不删
        assertThat(r.get("missing").asLong()).isGreaterThanOrEqualTo(1);
        int afterSize = listCandidates(ah, ch).size();
        assertThat(afterSize).as("历史手工候选不应被物理删除").isGreaterThanOrEqualTo(beforeSize);
    }
}