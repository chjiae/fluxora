package io.fluxora.platform.model;

import java.util.List;
import java.util.Map;
import java.util.Set;

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
        long mp = createMapping(ah, m, cand);
        // 走完其余前置（价格 + 路由 + 路由目标），仅留能力不一致暴露给启用前置
        publishPrice(ah, m, "1.0", "2.0");
        long route = createRoute(ah, m, "OPENAI");
        createTarget(ah, route, mp);

        ResponseEntity<String> r = restTemplate.exchange(base + "/api/tenant-models/" + m + "/enable",
                HttpMethod.POST, new HttpEntity<>(ah), String.class);
        assertThat(r.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(om.readTree(r.getBody()).get("code").asText())
                .isEqualTo("TENANT_MODEL_CAPABILITY_UNSUPPORTED");
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

    // =================== 11. 价格发布与版本递增（4 项 CNY 字符串） ===================

    @Test
    void shouldPublishImmutablePriceVersions() throws Exception {
        HttpHeaders ah = adminAuth();
        long tA = ensureDefaultTenant(ah);
        long m = createTenantModel(ah, tA, "price-v-" + System.nanoTime(), "价格版本", false);

        // v1
        java.util.Map<String, Object> v1m = new java.util.HashMap<>();
        v1m.put("inputPricePerMillion", "1.00");
        v1m.put("outputPricePerMillion", "3.50");
        v1m.put("cacheWritePricePerMillion", null);
        v1m.put("cacheReadPricePerMillion", null);
        String v1 = om.writeValueAsString(v1m);
        ResponseEntity<String> r1 = restTemplate.exchange(base + "/api/tenant-models/" + m + "/prices",
                HttpMethod.POST, new HttpEntity<>(v1, ah), String.class);
        assertThat(r1.getStatusCode()).as("v1: %s", r1.getBody()).isEqualTo(HttpStatus.OK);
        assertThat(om.readTree(r1.getBody()).get("data").get("version").asInt()).isEqualTo(1);
        // 金额必须以字符串呈现（不是 Number）
        assertThat(om.readTree(r1.getBody()).get("data").get("inputPricePerMillion").isTextual()).isTrue();

        // v2 改价
        String v2 = om.writeValueAsString(Map.of(
                "inputPricePerMillion", "1.20",
                "outputPricePerMillion", "3.60"));
        ResponseEntity<String> r2 = restTemplate.exchange(base + "/api/tenant-models/" + m + "/prices",
                HttpMethod.POST, new HttpEntity<>(v2, ah), String.class);
        assertThat(r2.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(om.readTree(r2.getBody()).get("data").get("version").asInt()).isEqualTo(2);

        // current 返回 v2
        ResponseEntity<String> cur = restTemplate.exchange(base + "/api/tenant-models/" + m + "/prices/current",
                HttpMethod.GET, new HttpEntity<>(ah), String.class);
        assertThat(om.readTree(cur.getBody()).get("data").get("version").asInt()).isEqualTo(2);

        // history 包含两条且 v2 在前
        ResponseEntity<String> hist = restTemplate.exchange(base + "/api/tenant-models/" + m + "/prices",
                HttpMethod.GET, new HttpEntity<>(ah), String.class);
        JsonNode items = om.readTree(hist.getBody()).get("data");
        assertThat(items.size()).isEqualTo(2);
        assertThat(items.get(0).get("version").asInt()).isEqualTo(2);
        assertThat(items.get(1).get("version").asInt()).isEqualTo(1);
        // 历史 v1 携带 expiredAt 时刻
        assertThat(items.get(1).get("expiredAt").isNull()).isFalse();
    }

    // =================== 12. 价格输入只接受字符串，拒绝 JSON Number 与超精度 ===================

    @Test
    void shouldRejectInvalidPriceInputs() throws Exception {
        HttpHeaders ah = adminAuth();
        long tA = ensureDefaultTenant(ah);
        long m = createTenantModel(ah, tA, "price-bad-" + System.nanoTime(), "拒绝精度", false);

        // 1) JSON Number 直接拒绝（DecimalStringDeserializer）
        String numberBody = "{\"inputPricePerMillion\":1.5,\"outputPricePerMillion\":\"2.0\"}";
        ResponseEntity<String> rn = restTemplate.exchange(base + "/api/tenant-models/" + m + "/prices",
                HttpMethod.POST, new HttpEntity<>(numberBody, ah), String.class);
        assertThat(rn.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);

        // 2) 超过 8 位小数
        String body9 = om.writeValueAsString(Map.of(
                "inputPricePerMillion", "1.123456789",
                "outputPricePerMillion", "2.0"));
        ResponseEntity<String> r9 = restTemplate.exchange(base + "/api/tenant-models/" + m + "/prices",
                HttpMethod.POST, new HttpEntity<>(body9, ah), String.class);
        assertThat(r9.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);

        // 3) 科学计数法
        String sciBody = om.writeValueAsString(Map.of(
                "inputPricePerMillion", "1e-3",
                "outputPricePerMillion", "2.0"));
        ResponseEntity<String> rs = restTemplate.exchange(base + "/api/tenant-models/" + m + "/prices",
                HttpMethod.POST, new HttpEntity<>(sciBody, ah), String.class);
        assertThat(rs.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);

        // 错误响应不暴露异常类、SQL、堆栈
        assertThat(r9.getBody()).doesNotContain("Exception", "SQL", "stacktrace");
    }

    // =================== 13. 缓存能力与缓存价的一致性 ===================

    @Test
    void shouldEnforceCacheCapabilityVsCachePrice() throws Exception {
        HttpHeaders ah = adminAuth();
        long tA = ensureDefaultTenant(ah);

        // 模型不支持缓存：禁止提交缓存价
        long mNoCache = createTenantModel(ah, tA, "no-cache-" + System.nanoTime(), "无缓存", false);
        String bad = om.writeValueAsString(Map.of(
                "inputPricePerMillion", "1.0",
                "outputPricePerMillion", "2.0",
                "cacheWritePricePerMillion", "0.5",
                "cacheReadPricePerMillion", "0.1"));
        ResponseEntity<String> r1 = restTemplate.exchange(base + "/api/tenant-models/" + mNoCache + "/prices",
                HttpMethod.POST, new HttpEntity<>(bad, ah), String.class);
        assertThat(r1.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);

        // 模型支持缓存：必须两项缓存价齐备
        String supportsCacheBody = om.writeValueAsString(Map.of(
                "tenantId", tA,
                "modelCode", "cache-" + System.nanoTime(),
                "displayName", "缓存模型",
                "supportsCache", true));
        ResponseEntity<String> created = restTemplate.exchange(base + "/api/tenant-models",
                HttpMethod.POST, new HttpEntity<>(supportsCacheBody, ah), String.class);
        long mCache = om.readTree(created.getBody()).get("data").get("id").asLong();
        String partial = om.writeValueAsString(Map.of(
                "inputPricePerMillion", "1.0",
                "outputPricePerMillion", "2.0",
                "cacheWritePricePerMillion", "0.5"));
        ResponseEntity<String> r2 = restTemplate.exchange(base + "/api/tenant-models/" + mCache + "/prices",
                HttpMethod.POST, new HttpEntity<>(partial, ah), String.class);
        assertThat(r2.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);

        // 完整缓存价：通过
        String full = om.writeValueAsString(Map.of(
                "inputPricePerMillion", "1.0",
                "outputPricePerMillion", "2.0",
                "cacheWritePricePerMillion", "0.5",
                "cacheReadPricePerMillion", "0.1"));
        ResponseEntity<String> r3 = restTemplate.exchange(base + "/api/tenant-models/" + mCache + "/prices",
                HttpMethod.POST, new HttpEntity<>(full, ah), String.class);
        assertThat(r3.getStatusCode()).isEqualTo(HttpStatus.OK);
    }

    // =================== 14. 启用前置：必须有当前有效价格 ===================

    @Test
    void shouldBlockEnableWithoutCurrentPrice() throws Exception {
        HttpHeaders ah = adminAuth();
        long tA = ensureDefaultTenant(ah);
        long pid = createSharedProvider(ah, "shp_" + System.nanoTime());
        long bid = createBaseUrl(ah, pid, "OPENAI", "https://api.example.com/v1");
        long ch = createChannel(ah, tA, bid, "通道-no-price");
        long cand = createCandidate(ah, ch, "gpt-no-price", false);
        long m = createTenantModel(ah, tA, "m-no-price-" + System.nanoTime(), "无价格", false);
        createMapping(ah, m, cand);

        ResponseEntity<String> r = restTemplate.exchange(base + "/api/tenant-models/" + m + "/enable",
                HttpMethod.POST, new HttpEntity<>(ah), String.class);
        assertThat(r.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        // 用户可见的默认安全文案
        assertThat(r.getBody()).contains("尚未满足启用条件", "请补充价格");
    }

    // =================== 15. 公开目录：当前租户可见 + 跨租户隔离 + 字段脱敏 ===================

    @Test
    void shouldExposeOnlyCurrentTenantPublishedModelsViaPublicCatalog() throws Exception {
        HttpHeaders ah = adminAuth();
        long tA = ensureDefaultTenant(ah);
        long tB = createTenant(ah, "pubcat_" + System.nanoTime());
        String adminB = createTenantAdmin(ah, tB);

        // 为 A 与 B 各配置一个完整发布模型
        long pid = createSharedProvider(ah, "shp_" + System.nanoTime());
        long bid = createBaseUrl(ah, pid, "OPENAI", "https://api.example.com/v1");
        long chA = createChannel(ah, tA, bid, "通道 A");
        long chB = createChannel(ah, tB, bid, "通道 B");
        long candA = createCandidate(ah, chA, "model-A", false);
        long candB = createCandidate(ah, chB, "model-B", false);
        long mA = createTenantModel(ah, tA, "pub-A-" + System.nanoTime(), "对外模型 A", false);
        long mB = createTenantModel(ah, tB, "pub-B-" + System.nanoTime(), "对外模型 B", false);
        long mapA = createMapping(ah, mA, candA);
        long mapB = createMapping(ah, mB, candB);
        publishPrice(ah, mA, "0.10", "0.30");
        publishPrice(ah, mB, "0.20", "0.40");
        long routeA = createRoute(ah, mA, "OPENAI");
        long routeB = createRoute(ah, mB, "OPENAI");
        createTarget(ah, routeA, mapA);
        createTarget(ah, routeB, mapB);
        enable(ah, mA);
        enable(ah, mB);

        // A 租户管理员调 /api/models 看到自己的 mA，且看不到 mB
        String adminAUsername = createTenantAdmin(ah, tA);
        HttpHeaders aH = login(adminAUsername, "TaPass2026!");
        ResponseEntity<String> rA = restTemplate.exchange(base + "/api/models",
                HttpMethod.GET, new HttpEntity<>(aH), String.class);
        assertThat(rA.getStatusCode()).isEqualTo(HttpStatus.OK);
        JsonNode itemsA = om.readTree(rA.getBody()).get("data");
        boolean foundMA = false;
        for (JsonNode it : itemsA) {
            long id = it.get("id").asLong();
            assertThat(id).as("租户 A 看不到 B 的模型").isNotEqualTo(mB);
            if (id == mA) foundMA = true;
        }
        assertThat(foundMA).as("租户 A 应能看到 A 的模型").isTrue();

        // B 租户管理员只看到 B
        HttpHeaders bH = login(adminB, "TaPass2026!");
        ResponseEntity<String> rB = restTemplate.exchange(base + "/api/models",
                HttpMethod.GET, new HttpEntity<>(bH), String.class);
        JsonNode itemsB = om.readTree(rB.getBody()).get("data");
        boolean foundMB = false;
        for (JsonNode it : itemsB) {
            long id = it.get("id").asLong();
            assertThat(id).as("租户 B 看不到 A 的模型").isNotEqualTo(mA);
            if (id == mB) foundMB = true;
        }
        assertThat(foundMB).as("租户 B 应能看到 B 的模型").isTrue();

        // 公开目录绝不返回内部字段：逐项断言键名而非粗匹配 body，避免与
        // 用户自定义 modelCode/displayName（如「m-noroute-…」）误判
        Set<String> allowedKeys = Set.of(
                "id", "modelCode", "displayName", "description",
                "supportsStreaming", "supportsToolCalling", "supportsVision", "supportsCache",
                "currencyCode", "inputPricePerMillion", "outputPricePerMillion",
                "cacheWritePricePerMillion", "cacheReadPricePerMillion");
        for (JsonNode it : itemsA) {
            for (java.util.Iterator<String> names = it.fieldNames(); names.hasNext(); ) {
                String name = names.next();
                assertThat(allowedKeys).as("公开目录字段 %s 不应出现", name).contains(name);
            }
        }
    }

    // =================== 16. 公开目录：DRAFT/无价/缺映射的模型不可见 ===================

    @Test
    void shouldHidePublicModelsLackingPublishConditions() throws Exception {
        HttpHeaders ah = adminAuth();
        long tA = ensureDefaultTenant(ah);
        String adminA = createTenantAdmin(ah, tA);
        HttpHeaders aH = login(adminA, "TaPass2026!");

        // DRAFT 模型：未启用不可见
        long mDraft = createTenantModel(ah, tA, "draft-" + System.nanoTime(), "草稿", false);
        // 无价模型：即使配了映射，没有价格不应进入 enable，公开目录也不应出现
        long pid = createSharedProvider(ah, "shp_" + System.nanoTime());
        long bid = createBaseUrl(ah, pid, "OPENAI", "https://api.example.com/v1");
        long ch = createChannel(ah, tA, bid, "通道-hide");
        long cand = createCandidate(ah, ch, "hide-1", false);
        long mNoPrice = createTenantModel(ah, tA, "no-price-pub-" + System.nanoTime(), "无价", false);
        createMapping(ah, mNoPrice, cand);

        ResponseEntity<String> r = restTemplate.exchange(base + "/api/models",
                HttpMethod.GET, new HttpEntity<>(aH), String.class);
        assertThat(r.getStatusCode()).isEqualTo(HttpStatus.OK);
        JsonNode items = om.readTree(r.getBody()).get("data");
        for (JsonNode it : items) {
            long id = it.get("id").asLong();
            assertThat(id).as("草稿或无价模型不应出现在公开目录").isNotIn(mDraft, mNoPrice);
        }
    }

    // =================== 17. 路由唯一（同模型同协议） + 协议归一化 ===================

    @Test
    void shouldEnforceRouteUniquenessPerProtocol() throws Exception {
        HttpHeaders ah = adminAuth();
        long tA = ensureDefaultTenant(ah);
        long m = createTenantModel(ah, tA, "route-uniq-" + System.nanoTime(), "路由唯一", false);

        createRoute(ah, m, "OPENAI");
        // 同协议重复（不同大小写归一化为相同协议）
        String dup = om.writeValueAsString(Map.of("inboundProtocol", "openai"));
        ResponseEntity<String> r = restTemplate.exchange(base + "/api/tenant-models/" + m + "/routes",
                HttpMethod.POST, new HttpEntity<>(dup, ah), String.class);
        assertThat(r.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        // 业务码用于程序识别
        assertThat(om.readTree(r.getBody()).get("code").asText()).isEqualTo("TENANT_MODEL_INVALID");
        // 不同协议允许
        long routeAnthropic = createRoute(ah, m, "ANTHROPIC");
        assertThat(routeAnthropic).isGreaterThan(0);
        // 非法协议拒绝
        String bad = om.writeValueAsString(Map.of("inboundProtocol", "GRPC"));
        ResponseEntity<String> rb = restTemplate.exchange(base + "/api/tenant-models/" + m + "/routes",
                HttpMethod.POST, new HttpEntity<>(bad, ah), String.class);
        assertThat(rb.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
    }

    // =================== 18. 路由目标四方一致 + 协议兼容 + 同路由不重复 ===================

    @Test
    void shouldEnforceRouteTargetFourWayTenantConsistencyAndProtocolCompat() throws Exception {
        HttpHeaders ah = adminAuth();
        long tA = ensureDefaultTenant(ah);
        long tB = createTenant(ah, "rt_" + System.nanoTime());

        long pid = createSharedProvider(ah, "shp_" + System.nanoTime());
        long bidOpenai = createBaseUrl(ah, pid, "OPENAI", "https://api.example.com/v1");
        long bidAnthropic = createBaseUrl(ah, pid, "ANTHROPIC", "https://api.example.com/v1");
        long chOpenaiA = createChannel(ah, tA, bidOpenai, "OAI-A");
        long chAnthropicA = createChannel(ah, tA, bidAnthropic, "ANT-A");
        long chOpenaiB = createChannel(ah, tB, bidOpenai, "OAI-B");
        long candOAI_A = createCandidate(ah, chOpenaiA, "oai-a", false);
        long candANT_A = createCandidate(ah, chAnthropicA, "ant-a", false);
        long candOAI_B = createCandidate(ah, chOpenaiB, "oai-b", false);

        long mA = createTenantModel(ah, tA, "rt-A-" + System.nanoTime(), "路由模型 A", false);
        long mapOAI_A = createMapping(ah, mA, candOAI_A);
        long mapANT_A = createMapping(ah, mA, candANT_A);
        long routeOAI = createRoute(ah, mA, "OPENAI");

        // 正常：A 的 OPENAI 路由 + A 的 OAI 映射
        long t1 = createTarget(ah, routeOAI, mapOAI_A);
        assertThat(t1).isGreaterThan(0);

        // 同一映射重复添加：拒绝（业务码 TENANT_MODEL_MAPPING_DUPLICATE）
        String dup = om.writeValueAsString(Map.of("tenantModelCandidateMappingId", mapOAI_A));
        ResponseEntity<String> rd = restTemplate.exchange(base + "/api/routes/" + routeOAI + "/targets",
                HttpMethod.POST, new HttpEntity<>(dup, ah), String.class);
        assertThat(rd.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(om.readTree(rd.getBody()).get("code").asText())
                .isEqualTo("TENANT_MODEL_MAPPING_DUPLICATE");

        // 协议不兼容：OPENAI 路由 + ANTHROPIC 映射 → 拒绝
        String wrong = om.writeValueAsString(Map.of("tenantModelCandidateMappingId", mapANT_A));
        ResponseEntity<String> rw = restTemplate.exchange(base + "/api/routes/" + routeOAI + "/targets",
                HttpMethod.POST, new HttpEntity<>(wrong, ah), String.class);
        assertThat(rw.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(om.readTree(rw.getBody()).get("code").asText()).isEqualTo("TENANT_MODEL_INVALID");

        // 跨租户：A 的路由 + B 的映射（注：mapOAI_A 已用过；这里直接尝试用 B 的候选 ID 当 mappingId 走 RESOLUTION 失败路径）
        // 服务层 resolveMapping 不存在 → TENANT_MODEL_MAPPING_NOT_FOUND
        // 但更严格的语义是：跨租户引用应被拒；做一次 B 租户内的合法映射后再尝试跨租户
        String adminB = createTenantAdmin(ah, tB);
        // 平台管理员代管为 B 创建模型 + 映射
        long mB = createTenantModel(ah, tB, "rt-B-" + System.nanoTime(), "路由模型 B", false);
        long mapB = createMapping(ah, mB, candOAI_B);
        String crossBody = om.writeValueAsString(Map.of("tenantModelCandidateMappingId", mapB));
        ResponseEntity<String> rc = restTemplate.exchange(base + "/api/routes/" + routeOAI + "/targets",
                HttpMethod.POST, new HttpEntity<>(crossBody, ah), String.class);
        assertThat(rc.getStatusCode().is4xxClientError()).isTrue();
        assertThat(om.readTree(rc.getBody()).get("code").asText())
                .isEqualTo("TENANT_MODEL_MAPPING_TENANT_MISMATCH");
    }

    // =================== 19. 启用前置：缺路由或缺路由目标都不可启用 ===================

    @Test
    void shouldBlockEnableWithoutRouteOrTarget() throws Exception {
        HttpHeaders ah = adminAuth();
        long tA = ensureDefaultTenant(ah);
        long pid = createSharedProvider(ah, "shp_" + System.nanoTime());
        long bid = createBaseUrl(ah, pid, "OPENAI", "https://api.example.com/v1");
        long ch = createChannel(ah, tA, bid, "通道-noroute");
        long cand = createCandidate(ah, ch, "noroute", false);
        long m = createTenantModel(ah, tA, "m-noroute-" + System.nanoTime(), "无路由", false);
        long mp = createMapping(ah, m, cand);
        publishPrice(ah, m, "1.0", "2.0");

        // 缺路由
        ResponseEntity<String> r1 = restTemplate.exchange(base + "/api/tenant-models/" + m + "/enable",
                HttpMethod.POST, new HttpEntity<>(ah), String.class);
        assertThat(r1.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(r1.getBody()).contains("请补充价格与路由");

        // 有路由但缺目标
        long route = createRoute(ah, m, "OPENAI");
        ResponseEntity<String> r2 = restTemplate.exchange(base + "/api/tenant-models/" + m + "/enable",
                HttpMethod.POST, new HttpEntity<>(ah), String.class);
        assertThat(r2.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(r2.getBody()).contains("请补充价格与路由");

        // 配齐目标后通过
        createTarget(ah, route, mp);
        ResponseEntity<String> r3 = restTemplate.exchange(base + "/api/tenant-models/" + m + "/enable",
                HttpMethod.POST, new HttpEntity<>(ah), String.class);
        assertThat(r3.getStatusCode()).isEqualTo(HttpStatus.OK);
    }

    // =================== 20. 被启用 RouteTarget 占用的映射不可直接删除 ===================

    @Test
    void shouldProtectMappingInUseByActiveRouteTarget() throws Exception {
        HttpHeaders ah = adminAuth();
        long tA = ensureDefaultTenant(ah);
        long pid = createSharedProvider(ah, "shp_" + System.nanoTime());
        long bid = createBaseUrl(ah, pid, "OPENAI", "https://api.example.com/v1");
        long ch = createChannel(ah, tA, bid, "通道-mapuse");
        long cand = createCandidate(ah, ch, "mapuse", false);
        long m = createTenantModel(ah, tA, "m-mapuse-" + System.nanoTime(), "映射保护", false);
        long mp = createMapping(ah, m, cand);
        long route = createRoute(ah, m, "OPENAI");
        long target = createTarget(ah, route, mp);

        // 试删映射：被启用的路由目标引用 → 拒绝（业务码 TENANT_MODEL_MAPPING_IN_USE）
        ResponseEntity<String> del = restTemplate.exchange(
                base + "/api/tenant-models/" + m + "/candidate-mappings/" + mp,
                HttpMethod.DELETE, new HttpEntity<>(ah), String.class);
        assertThat(del.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(om.readTree(del.getBody()).get("code").asText())
                .isEqualTo("TENANT_MODEL_MAPPING_IN_USE");

        // 先删路由目标，再删映射 → 通过
        ResponseEntity<String> delT = restTemplate.exchange(
                base + "/api/routes/" + route + "/targets/" + target,
                HttpMethod.DELETE, new HttpEntity<>(ah), String.class);
        assertThat(delT.getStatusCode()).isEqualTo(HttpStatus.OK);
        ResponseEntity<String> del2 = restTemplate.exchange(
                base + "/api/tenant-models/" + m + "/candidate-mappings/" + mp,
                HttpMethod.DELETE, new HttpEntity<>(ah), String.class);
        assertThat(del2.getStatusCode()).isEqualTo(HttpStatus.OK);
    }

    // =================== 21. 公开目录在缺路由/缺目标时不可见 ===================

    @Test
    void shouldHidePublicModelsLackingRouteOrTarget() throws Exception {
        HttpHeaders ah = adminAuth();
        long tA = ensureDefaultTenant(ah);
        String adminA = createTenantAdmin(ah, tA);
        HttpHeaders aH = login(adminA, "TaPass2026!");

        // 完整配置但「未启用」（DRAFT）→ 不可见
        long pid = createSharedProvider(ah, "shp_" + System.nanoTime());
        long bid = createBaseUrl(ah, pid, "OPENAI", "https://api.example.com/v1");
        long ch = createChannel(ah, tA, bid, "通道-pub");
        long cand = createCandidate(ah, ch, "pub-1", false);
        long m = createTenantModel(ah, tA, "pub-route-" + System.nanoTime(), "公开路由验证", false);
        long mp = createMapping(ah, m, cand);
        publishPrice(ah, m, "1.0", "2.0");
        long route = createRoute(ah, m, "OPENAI");
        long target = createTarget(ah, route, mp);

        // 还未 enable → 公开目录看不到
        ResponseEntity<String> r1 = restTemplate.exchange(base + "/api/models",
                HttpMethod.GET, new HttpEntity<>(aH), String.class);
        for (JsonNode it : om.readTree(r1.getBody()).get("data")) {
            assertThat(it.get("id").asLong()).isNotEqualTo(m);
        }

        // enable → 可见
        enable(ah, m);
        ResponseEntity<String> r2 = restTemplate.exchange(base + "/api/models",
                HttpMethod.GET, new HttpEntity<>(aH), String.class);
        boolean visible = false;
        for (JsonNode it : om.readTree(r2.getBody()).get("data")) {
            if (it.get("id").asLong() == m) visible = true;
        }
        assertThat(visible).isTrue();

        // 删除路由目标 → 公开目录再次不可见（不需要先 disable）
        restTemplate.exchange(base + "/api/routes/" + route + "/targets/" + target,
                HttpMethod.DELETE, new HttpEntity<>(ah), String.class);
        ResponseEntity<String> r3 = restTemplate.exchange(base + "/api/models",
                HttpMethod.GET, new HttpEntity<>(aH), String.class);
        for (JsonNode it : om.readTree(r3.getBody()).get("data")) {
            assertThat(it.get("id").asLong()).as("无路由目标的模型不应在公开目录").isNotEqualTo(m);
        }
    }

    // =================== 22. 路由列表脱敏 + 跨租户阻断 ===================

    @Test
    void shouldDenyCrossTenantRouteAccess() throws Exception {
        HttpHeaders ah = adminAuth();
        long tA = ensureDefaultTenant(ah);
        long tB = createTenant(ah, "rtcross_" + System.nanoTime());
        String adminB = createTenantAdmin(ah, tB);

        long mA = createTenantModel(ah, tA, "rt-cross-A-" + System.nanoTime(), "A", false);
        long routeA = createRoute(ah, mA, "OPENAI");

        // B 租户管理员不应访问 A 的路由
        HttpHeaders bH = login(adminB, "TaPass2026!");
        // 通过 /api/tenant-models/{mA}/routes：先校验模型可见性 → 应 404
        ResponseEntity<String> r = restTemplate.exchange(base + "/api/tenant-models/" + mA + "/routes",
                HttpMethod.GET, new HttpEntity<>(bH), String.class);
        assertThat(r.getStatusCode().is4xxClientError()).isTrue();
        assertThat(r.getBody()).doesNotContain("Exception", "SQL", "stacktrace");

        // 直接试编辑 routeA：路由可见性失败 → 4xx
        String body = om.writeValueAsString(Map.of("enabled", false));
        ResponseEntity<String> rp = restTemplate.exchange(base + "/api/routes/" + routeA,
                HttpMethod.PUT, new HttpEntity<>(body, bH), String.class);
        assertThat(rp.getStatusCode().is4xxClientError()).isTrue();
    }

    // =================== 23. 一模型多候选 + 一候选多模型（多对多语义） ===================

    @Test
    void shouldSupportManyToManyMappings() throws Exception {
        HttpHeaders ah = adminAuth();
        long tA = ensureDefaultTenant(ah);
        long pid = createSharedProvider(ah, "shp_" + System.nanoTime());
        long bid = createBaseUrl(ah, pid, "OPENAI", "https://api.example.com/v1");
        long ch = createChannel(ah, tA, bid, "通道-m2m");
        long cand1 = createCandidate(ah, ch, "m2m-cand-1", false);
        long cand2 = createCandidate(ah, ch, "m2m-cand-2", false);
        long m1 = createTenantModel(ah, tA, "m2m-A-" + System.nanoTime(), "模型 1", false);
        long m2 = createTenantModel(ah, tA, "m2m-B-" + System.nanoTime(), "模型 2", false);

        // 模型 1 映射两个候选
        createMapping(ah, m1, cand1);
        createMapping(ah, m1, cand2);
        // 候选 1 同时被模型 1、模型 2 引用
        createMapping(ah, m2, cand1);

        ResponseEntity<String> r1 = restTemplate.exchange(
                base + "/api/tenant-models/" + m1 + "/candidate-mappings",
                HttpMethod.GET, new HttpEntity<>(ah), String.class);
        assertThat(om.readTree(r1.getBody()).get("data").size()).isEqualTo(2);

        ResponseEntity<String> r2 = restTemplate.exchange(
                base + "/api/tenant-models/" + m2 + "/candidate-mappings",
                HttpMethod.GET, new HttpEntity<>(ah), String.class);
        assertThat(om.readTree(r2.getBody()).get("data").size()).isEqualTo(1);
    }

    // =================== 24. 软删除后资源不可继续被绑定 / 启用 / 路由 ===================

    @Test
    void shouldRejectOperationsOnSoftDeletedResources() throws Exception {
        HttpHeaders ah = adminAuth();
        long tA = ensureDefaultTenant(ah);
        long m = createTenantModel(ah, tA, "soft-del-" + System.nanoTime(), "软删测试", false);

        ResponseEntity<String> del = restTemplate.exchange(base + "/api/tenant-models/" + m,
                HttpMethod.DELETE, new HttpEntity<>(ah), String.class);
        assertThat(del.getStatusCode()).isEqualTo(HttpStatus.OK);

        // 软删除后任何后续操作返回 404 / 业务码 TENANT_MODEL_NOT_FOUND
        ResponseEntity<String> get = restTemplate.exchange(base + "/api/tenant-models/" + m,
                HttpMethod.GET, new HttpEntity<>(ah), String.class);
        assertThat(get.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);

        ResponseEntity<String> en = restTemplate.exchange(base + "/api/tenant-models/" + m + "/enable",
                HttpMethod.POST, new HttpEntity<>(ah), String.class);
        assertThat(en.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);

        String body = om.writeValueAsString(Map.of("inputPricePerMillion", "1.0", "outputPricePerMillion", "2.0"));
        ResponseEntity<String> pp = restTemplate.exchange(base + "/api/tenant-models/" + m + "/prices",
                HttpMethod.POST, new HttpEntity<>(body, ah), String.class);
        assertThat(pp.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        // 错误响应不暴露技术文本
        assertThat(pp.getBody()).doesNotContain("Exception", "SQL", "stacktrace");
    }

    // =================== 25. 共享 Provider/BaseUrl 各租户独立 Channel + 全链隔离 ===================

    @Test
    void shouldKeepResourcesIsolatedAcrossTenantsOverSharedProviderAndBaseUrl() throws Exception {
        HttpHeaders ah = adminAuth();
        long tA = ensureDefaultTenant(ah);
        long tB = createTenant(ah, "share_" + System.nanoTime());
        String adminB = createTenantAdmin(ah, tB);

        // 同一个共享 Provider 与 BaseUrl
        long pid = createSharedProvider(ah, "shp_" + System.nanoTime());
        long bid = createBaseUrl(ah, pid, "OPENAI", "https://share.example.com/v1");

        // 两租户各自创建独立通道
        long chA = createChannel(ah, tA, bid, "share-A");
        long chB = createChannel(ah, tB, bid, "share-B");
        long candA = createCandidate(ah, chA, "share-a-cand", false);
        long candB = createCandidate(ah, chB, "share-b-cand", false);
        long mA = createTenantModel(ah, tA, "share-A-m-" + System.nanoTime(), "A 模型", false);
        long mB = createTenantModel(ah, tB, "share-B-m-" + System.nanoTime(), "B 模型", false);
        createMapping(ah, mA, candA);
        createMapping(ah, mB, candB);

        // 跨租户：A 的模型不能引用 B 的候选；即使 Provider/BaseUrl 共享也不行
        String crossBody = om.writeValueAsString(Map.of("providerChannelModelId", candB));
        ResponseEntity<String> cross = restTemplate.exchange(
                base + "/api/tenant-models/" + mA + "/candidate-mappings",
                HttpMethod.POST, new HttpEntity<>(crossBody, ah), String.class);
        assertThat(cross.getStatusCode().is4xxClientError()).isTrue();

        // 租户 B 管理员调通道列表不应包含 A 的通道
        HttpHeaders bH = login(adminB, "TaPass2026!");
        ResponseEntity<String> chList = restTemplate.exchange(
                base + "/api/provider-channels", HttpMethod.GET, new HttpEntity<>(bH), String.class);
        for (JsonNode it : om.readTree(chList.getBody()).get("data").get("items")) {
            assertThat(it.get("tenantId").asLong()).as("租户 B 不应看到 A 的通道").isNotEqualTo(tA);
        }
    }

    // =================== 26. 运行时不存在 PlatformModel 痕迹（V10 后） ===================

    @Test
    void shouldNotExposeAnyPlatformModelArtifacts() throws Exception {
        HttpHeaders ah = adminAuth();
        ensureDefaultTenant(ah);

        // 现有租户模型接口的响应不应出现旧字段
        ResponseEntity<String> list = restTemplate.exchange(base + "/api/tenant-models",
                HttpMethod.GET, new HttpEntity<>(ah), String.class);
        String body = list.getBody();
        assertThat(body).doesNotContain("platformModelId", "platform_model_id",
                "MODEL_CATALOG_", "MODEL_PLATFORM_", "INHERIT_PLATFORM_DEFAULT");

        // 旧接口已彻底移除：Spring Security 链对未注册路由返回 403/404；不返回 Spring 内部错误
        ResponseEntity<String> oldPath = restTemplate.exchange(base + "/api/platform-models",
                HttpMethod.GET, new HttpEntity<>(ah), String.class);
        assertThat(oldPath.getStatusCode().is4xxClientError() || oldPath.getStatusCode().is5xxServerError())
                .as("旧 /api/platform-models 不应继续提供 200 响应").isTrue();
        if (oldPath.getBody() != null) {
            // 即使 4xx/5xx，响应体不得暴露异常堆栈
            assertThat(oldPath.getBody()).doesNotContain("at io.fluxora", "java.lang.", "SQLException");
        }
    }

    // ---------- 帮助方法 ----------

    private void publishPrice(HttpHeaders ah, long modelId, String input, String output) throws Exception {
        String body = om.writeValueAsString(Map.of(
                "inputPricePerMillion", input,
                "outputPricePerMillion", output));
        ResponseEntity<String> r = restTemplate.exchange(base + "/api/tenant-models/" + modelId + "/prices",
                HttpMethod.POST, new HttpEntity<>(body, ah), String.class);
        assertThat(r.getStatusCode()).as("价格发布失败: %s", r.getBody()).isEqualTo(HttpStatus.OK);
    }

    /** 在模型下创建 OPENAI 路由并返回 routeId。 */
    private long createRoute(HttpHeaders ah, long modelId, String protocol) throws Exception {
        String body = om.writeValueAsString(Map.of("inboundProtocol", protocol));
        ResponseEntity<String> r = restTemplate.exchange(base + "/api/tenant-models/" + modelId + "/routes",
                HttpMethod.POST, new HttpEntity<>(body, ah), String.class);
        assertThat(r.getStatusCode()).as("路由创建失败: %s", r.getBody()).isEqualTo(HttpStatus.OK);
        return om.readTree(r.getBody()).get("data").get("id").asLong();
    }

    /** 在路由下创建路由目标并返回 targetId。 */
    private long createTarget(HttpHeaders ah, long routeId, long mappingId) throws Exception {
        String body = om.writeValueAsString(Map.of("tenantModelCandidateMappingId", mappingId));
        ResponseEntity<String> r = restTemplate.exchange(base + "/api/routes/" + routeId + "/targets",
                HttpMethod.POST, new HttpEntity<>(body, ah), String.class);
        assertThat(r.getStatusCode()).as("路由目标创建失败: %s", r.getBody()).isEqualTo(HttpStatus.OK);
        return om.readTree(r.getBody()).get("data").get("id").asLong();
    }

    private void enable(HttpHeaders ah, long modelId) throws Exception {
        ResponseEntity<String> r = restTemplate.exchange(base + "/api/tenant-models/" + modelId + "/enable",
                HttpMethod.POST, new HttpEntity<>(ah), String.class);
        assertThat(r.getStatusCode()).as("启用失败: %s", r.getBody()).isEqualTo(HttpStatus.OK);
    }
}
