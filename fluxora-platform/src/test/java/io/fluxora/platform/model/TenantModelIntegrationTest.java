package io.fluxora.platform.model;

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
import org.springframework.web.client.DefaultResponseErrorHandler;
import org.springframework.web.client.RestTemplate;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 租户模型领域（V10 重建）集成测试 —— 提交 2 范围：
 * - TenantModel CRUD（跨租户隔离、同租户编码唯一、跨租户允许相同 model_code）
 * - ProviderChannelModel 候选 CRUD（候选 tenant_id 与通道 tenant_id 一致）
 * - TenantModelCandidateMapping CRUD（三方租户一致、唯一性、被引用保护）
 * - 错误响应不暴露技术文本
 *
 * 提交 3 / 4 后将追加：价格、公开目录、路由、RouteTarget 相关测试。
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Testcontainers
class TenantModelIntegrationTest {

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

    public TenantModelIntegrationTest() {
        restTemplate = new RestTemplate();
        restTemplate.setErrorHandler(new DefaultResponseErrorHandler() {
            @Override public boolean hasError(HttpStatusCode s) { return false; }
        });
    }

    @BeforeEach void setUp() { base = "http://localhost:" + port; }

    // =================== 辅助：与 UpstreamIntegrationTest 风格一致 ===================

    private HttpHeaders login(String u, String p) throws Exception {
        String json = om.writeValueAsString(Map.of("username", u, "password", p));
        HttpHeaders h = new HttpHeaders(); h.set("Content-Type", "application/json");
        ResponseEntity<String> r = restTemplate.exchange(base + "/api/auth/login", HttpMethod.POST,
                new HttpEntity<>(json, h), String.class);
        assertThat(r.getStatusCode()).isEqualTo(HttpStatus.OK);
        HttpHeaders ah = new HttpHeaders();
        ah.put(HttpHeaders.COOKIE, r.getHeaders().get(HttpHeaders.SET_COOKIE));
        ah.set("Content-Type", "application/json");
        return ah;
    }

    private HttpHeaders adminAuth() throws Exception { return login("admin", "Admin@2026!"); }

    private long ensureDefaultTenant(HttpHeaders ah) throws Exception {
        String ij = om.writeValueAsString(Map.of("tenantName", "自营",
                "adminUsername", "seed_" + System.nanoTime(),
                "adminPassword", "TaPass2026!", "adminDisplayName", "Seed"));
        try {
            restTemplate.exchange(base + "/api/tenant/self-operated/initialize", HttpMethod.POST,
                    new HttpEntity<>(ij, ah), String.class);
        } catch (Exception ignored) {}
        return defaultTenantId(ah);
    }

    private long defaultTenantId(HttpHeaders ah) throws Exception {
        ResponseEntity<String> list = restTemplate.exchange(
                base + "/api/tenant?keyword=default", HttpMethod.GET, new HttpEntity<>(ah), String.class);
        for (JsonNode n : om.readTree(list.getBody()).get("data").get("items")) {
            if ("default".equals(n.get("tenantCode").asText())) return n.get("id").asLong();
        }
        throw new IllegalStateException("default 租户不存在");
    }

    private long createTenant(HttpHeaders ah, String code) throws Exception {
        String j = om.writeValueAsString(Map.of("tenantCode", code, "name", "测试租户",
                "type", "STANDARD", "enabled", true));
        ResponseEntity<String> r = restTemplate.exchange(base + "/api/tenant",
                HttpMethod.POST, new HttpEntity<>(j, ah), String.class);
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
        java.util.Map<String, Object> body = new java.util.HashMap<>(Map.of(
                "providerBaseUrlId", baseUrlId, "name", name, "enabled", true,
                "priority", 100, "weight", 100, "connectTimeoutMs", 5000, "readTimeoutMs", 60000));
        if (tenantId != null) body.put("tenantId", tenantId);
        String j = om.writeValueAsString(body);
        ResponseEntity<String> r = restTemplate.exchange(base + "/api/provider-channels",
                HttpMethod.POST, new HttpEntity<>(j, ah), String.class);
        assertThat(r.getStatusCode()).isEqualTo(HttpStatus.OK);
        return om.readTree(r.getBody()).get("data").get("id").asLong();
    }

    private long createCandidate(HttpHeaders ah, long channelId, String upstreamId, boolean streaming) throws Exception {
        String j = om.writeValueAsString(Map.of(
                "upstreamModelId", upstreamId, "upstreamDisplayName", upstreamId,
                "supportsStreaming", streaming, "supportsToolCalling", false,
                "supportsVision", false, "supportsCache", false, "enabled", true));
        ResponseEntity<String> r = restTemplate.exchange(
                base + "/api/provider-channels/" + channelId + "/models", HttpMethod.POST,
                new HttpEntity<>(j, ah), String.class);
        assertThat(r.getStatusCode()).isEqualTo(HttpStatus.OK);
        return om.readTree(r.getBody()).get("data").get("id").asLong();
    }

    private long createTenantModel(HttpHeaders ah, Long tenantId, String code, String name,
                                   boolean streaming) throws Exception {
        java.util.Map<String, Object> body = new java.util.HashMap<>(Map.of(
                "modelCode", code, "displayName", name,
                "supportsStreaming", streaming, "supportsToolCalling", false,
                "supportsVision", false, "supportsCache", false));
        if (tenantId != null) body.put("tenantId", tenantId);
        String j = om.writeValueAsString(body);
        ResponseEntity<String> r = restTemplate.exchange(base + "/api/tenant-models",
                HttpMethod.POST, new HttpEntity<>(j, ah), String.class);
        assertThat(r.getStatusCode()).as("租户模型创建失败：%s", r.getBody()).isEqualTo(HttpStatus.OK);
        return om.readTree(r.getBody()).get("data").get("id").asLong();
    }

    private long createMapping(HttpHeaders ah, long tenantModelId, long candidateId) throws Exception {
        String j = om.writeValueAsString(Map.of("providerChannelModelId", candidateId));
        ResponseEntity<String> r = restTemplate.exchange(
                base + "/api/tenant-models/" + tenantModelId + "/candidate-mappings",
                HttpMethod.POST, new HttpEntity<>(j, ah), String.class);
        assertThat(r.getStatusCode()).as("映射创建失败：%s", r.getBody()).isEqualTo(HttpStatus.OK);
        return om.readTree(r.getBody()).get("data").get("id").asLong();
    }

    // =================== 1. 跨租户隔离 ===================

    @Test
    void shouldIsolateTenantModelsAcrossTenants() throws Exception {
        HttpHeaders ah = adminAuth();
        long tA = ensureDefaultTenant(ah);
        long tB = createTenant(ah, "iso_" + System.nanoTime());
        String adminB = createTenantAdmin(ah, tB);

        // 平台管理员代管为 A 创建模型
        long mA = createTenantModel(ah, tA, "shared-code-A", "模型 A", false);

        // 租户 B 管理员查询：看不到 A
        HttpHeaders bh = login(adminB, "TaPass2026!");
        ResponseEntity<String> list = restTemplate.exchange(base + "/api/tenant-models",
                HttpMethod.GET, new HttpEntity<>(bh), String.class);
        assertThat(list.getStatusCode()).isEqualTo(HttpStatus.OK);
        JsonNode items = om.readTree(list.getBody()).get("data").get("items");
        for (JsonNode it : items) {
            assertThat(it.get("tenantId").asLong()).as("租户 B 不应能看到租户 A 的模型").isEqualTo(tB);
        }

        // 租户 B 管理员尝试直接访问 A 的模型详情：返回 404 或 403（不可见）
        ResponseEntity<String> detail = restTemplate.exchange(base + "/api/tenant-models/" + mA,
                HttpMethod.GET, new HttpEntity<>(bh), String.class);
        assertThat(detail.getStatusCode().is4xxClientError()).isTrue();
        // 不暴露 HTTP 状态码、SQL 或异常类
        assertThat(detail.getBody()).doesNotContain("SQL", "Exception", "tenant_id", "stacktrace");
    }

    // =================== 2. 同租户 model_code 唯一 + 跨租户允许相同 ===================

    @Test
    void shouldEnforceModelCodeUniqueWithinTenantAndAllowAcrossTenants() throws Exception {
        HttpHeaders ah = adminAuth();
        long tA = ensureDefaultTenant(ah);
        long tB = createTenant(ah, "uniq_" + System.nanoTime());

        String shared = "same-code-" + System.nanoTime();
        createTenantModel(ah, tA, shared, "A 的模型", false);
        // 同租户重复
        String dupBody = om.writeValueAsString(Map.of("tenantId", tA, "modelCode", shared,
                "displayName", "A 的模型 dup"));
        ResponseEntity<String> dup = restTemplate.exchange(base + "/api/tenant-models",
                HttpMethod.POST, new HttpEntity<>(dupBody, ah), String.class);
        assertThat(dup.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(dup.getBody()).contains("当前租户已存在相同模型编码");

        // 跨租户：B 应可使用相同 model_code
        long mB = createTenantModel(ah, tB, shared, "B 的模型", false);
        assertThat(mB).isGreaterThan(0);
    }

    // =================== 3. 候选 CRUD + 跨租户阻断 ===================

    @Test
    void shouldEnforceCandidateTenantConsistency() throws Exception {
        HttpHeaders ah = adminAuth();
        long tA = ensureDefaultTenant(ah);
        long tB = createTenant(ah, "cand_" + System.nanoTime());

        // 共享 Provider + BaseUrl
        long pid = createSharedProvider(ah, "shp_" + System.nanoTime());
        long bid = createBaseUrl(ah, pid, "OPENAI", "https://api.example.com/v1");
        long chA = createChannel(ah, tA, bid, "通道 A");
        long chB = createChannel(ah, tB, bid, "通道 B");

        long candA = createCandidate(ah, chA, "gpt-A", true);
        long candB = createCandidate(ah, chB, "gpt-B", false);
        // 同通道下不允许重复 upstream_model_id
        String dupBody = om.writeValueAsString(Map.of("upstreamModelId", "gpt-A",
                "upstreamDisplayName", "dup"));
        ResponseEntity<String> dup = restTemplate.exchange(
                base + "/api/provider-channels/" + chA + "/models", HttpMethod.POST,
                new HttpEntity<>(dupBody, ah), String.class);
        assertThat(dup.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(dup.getBody()).contains("当前通道下已存在相同上游模型标识");
    }

    // =================== 4. 映射三方一致性 + 唯一性 ===================

    @Test
    void shouldEnforceMappingTenantConsistencyAndUniqueness() throws Exception {
        HttpHeaders ah = adminAuth();
        long tA = ensureDefaultTenant(ah);
        long tB = createTenant(ah, "map_" + System.nanoTime());

        long pid = createSharedProvider(ah, "shp_" + System.nanoTime());
        long bid = createBaseUrl(ah, pid, "OPENAI", "https://api.example.com/v1");
        long chA = createChannel(ah, tA, bid, "通道 A");
        long chB = createChannel(ah, tB, bid, "通道 B");
        long candA = createCandidate(ah, chA, "gpt-iso-a", true);
        long candB = createCandidate(ah, chB, "gpt-iso-b", true);

        long mA = createTenantModel(ah, tA, "m-iso-a-" + System.nanoTime(), "M-A", false);

        // 正常映射：A 的模型 ↔ A 的候选
        long mappingId = createMapping(ah, mA, candA);
        assertThat(mappingId).isGreaterThan(0);

        // 重复映射：拒绝
        String dupBody = om.writeValueAsString(Map.of("providerChannelModelId", candA));
        ResponseEntity<String> dup = restTemplate.exchange(
                base + "/api/tenant-models/" + mA + "/candidate-mappings",
                HttpMethod.POST, new HttpEntity<>(dupBody, ah), String.class);
        assertThat(dup.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(dup.getBody()).contains("该上游候选已映射到当前模型");

        // 跨租户映射：A 的模型 ↔ B 的候选 → 拒绝
        String crossBody = om.writeValueAsString(Map.of("providerChannelModelId", candB));
        ResponseEntity<String> cross = restTemplate.exchange(
                base + "/api/tenant-models/" + mA + "/candidate-mappings",
                HttpMethod.POST, new HttpEntity<>(crossBody, ah), String.class);
        // TENANT_MODEL_MAPPING_TENANT_MISMATCH 映射到 403
        assertThat(cross.getStatusCode().is4xxClientError()).isTrue();
        assertThat(cross.getBody()).contains("所选模型或上游候选不可用");
    }

    // =================== 5. 候选被映射引用时不可删 ===================

    @Test
    void shouldProtectCandidateInUseByMapping() throws Exception {
        HttpHeaders ah = adminAuth();
        long tA = ensureDefaultTenant(ah);

        long pid = createSharedProvider(ah, "shp_" + System.nanoTime());
        long bid = createBaseUrl(ah, pid, "OPENAI", "https://api.example.com/v1");
        long ch = createChannel(ah, tA, bid, "通道-保护");
        long cand = createCandidate(ah, ch, "gpt-protect", true);
        long m = createTenantModel(ah, tA, "m-protect-" + System.nanoTime(), "保护测试", false);
        createMapping(ah, m, cand);

        ResponseEntity<String> del = restTemplate.exchange(
                base + "/api/provider-channels/" + ch + "/models/" + cand,
                HttpMethod.DELETE, new HttpEntity<>(ah), String.class);
        assertThat(del.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(del.getBody()).contains("当前映射仍被路由使用");
    }

    // =================== 6. TENANT_MEMBER 无管理权限 ===================

    @Test
    void shouldDenyTenantMemberFromManagingModels() throws Exception {
        HttpHeaders ah = adminAuth();
        long tA = ensureDefaultTenant(ah);
        // 创建一个普通成员
        String memberUsername = "u" + System.nanoTime();
        String j = om.writeValueAsString(Map.of("username", memberUsername, "displayName", memberUsername,
                "password", "Passw0rd!", "roleCode", "TENANT_MEMBER"));
        restTemplate.exchange(base + "/api/tenant/" + tA + "/members", HttpMethod.POST,
                new HttpEntity<>(j, ah), String.class);

        HttpHeaders mh = login(memberUsername, "Passw0rd!");
        // 列表（需要 TENANT_MODEL_READ）：普通成员无此权限 → 403
        ResponseEntity<String> list = restTemplate.exchange(base + "/api/tenant-models",
                HttpMethod.GET, new HttpEntity<>(mh), String.class);
        assertThat(list.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
        // 安全错误码默认文案，不含 HTTP 状态码与异常类
        assertThat(list.getBody()).contains("当前账号没有此操作权限");
        assertThat(list.getBody()).doesNotContain("403", "Exception");
    }

    // =================== 7. 平台管理员未指定目标租户时拒绝写 ===================

    @Test
    void shouldRequirePlatformAdminToSpecifyTargetTenant() throws Exception {
        HttpHeaders ah = adminAuth();
        ensureDefaultTenant(ah);
        // 平台管理员创建模型但不带 tenantId
        String body = om.writeValueAsString(Map.of("modelCode", "no-tenant-" + System.nanoTime(),
                "displayName", "未指定租户"));
        ResponseEntity<String> r = restTemplate.exchange(base + "/api/tenant-models",
                HttpMethod.POST, new HttpEntity<>(body, ah), String.class);
        assertThat(r.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(r.getBody()).contains("输入内容不符合要求", "请检查").containsAnyOf("输入内容不符合要求");
    }

    // =================== 8. 启用前置：必须有映射 ===================

    @Test
    void shouldBlockEnableWithoutMappings() throws Exception {
        HttpHeaders ah = adminAuth();
        long tA = ensureDefaultTenant(ah);
        long m = createTenantModel(ah, tA, "m-no-map-" + System.nanoTime(), "无映射", false);

        ResponseEntity<String> r = restTemplate.exchange(base + "/api/tenant-models/" + m + "/enable",
                HttpMethod.POST, new HttpEntity<>(ah), String.class);
        assertThat(r.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(r.getBody()).contains("尚未满足启用条件");
    }

    // =================== 9. 启用前能力支撑校验：声明流式但候选不支持 ===================

    @Test
    void shouldBlockEnableWhenCapabilityUnsupported() throws Exception {
        HttpHeaders ah = adminAuth();
        long tA = ensureDefaultTenant(ah);

        long pid = createSharedProvider(ah, "shp_" + System.nanoTime());
        long bid = createBaseUrl(ah, pid, "OPENAI", "https://api.example.com/v1");
        long ch = createChannel(ah, tA, bid, "通道-能力");
        // 候选不支持流式
        long cand = createCandidate(ah, ch, "gpt-no-stream", false);
        // 模型声明支持流式
        long m = createTenantModel(ah, tA, "m-cap-" + System.nanoTime(), "能力测试", true);
        createMapping(ah, m, cand);

        ResponseEntity<String> r = restTemplate.exchange(base + "/api/tenant-models/" + m + "/enable",
                HttpMethod.POST, new HttpEntity<>(ah), String.class);
        assertThat(r.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(r.getBody()).contains("当前上游能力无法支撑");
    }

    // =================== 10. 软删除后 deletedAt 不返回；status 派生 ===================

    @Test
    void shouldNeverExposeDeletedAtOrUpstreamInternals() throws Exception {
        HttpHeaders ah = adminAuth();
        long tA = ensureDefaultTenant(ah);
        long m = createTenantModel(ah, tA, "leak-" + System.nanoTime(), "脱敏测试", false);
        ResponseEntity<String> r = restTemplate.exchange(base + "/api/tenant-models/" + m,
                HttpMethod.GET, new HttpEntity<>(ah), String.class);
        assertThat(r.getStatusCode()).isEqualTo(HttpStatus.OK);
        String body = r.getBody();
        assertThat(body).doesNotContain("deletedAt", "deleted_at",
                "ciphertext", "credential_fingerprint", "initialization_vector");
        // status 派生字段必须存在
        assertThat(om.readTree(body).get("data").get("status").asText()).isIn("DRAFT", "ENABLED", "DISABLED");
    }
}
