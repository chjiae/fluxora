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
        // 先发布价格，让启用前置中的「无价格」分支不触发，从而暴露能力支撑校验失败
        publishPrice(ah, m, "1.0", "2.0");

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
        createMapping(ah, mA, candA);
        createMapping(ah, mB, candB);
        publishPrice(ah, mA, "0.10", "0.30");
        publishPrice(ah, mB, "0.20", "0.40");
        enable(ah, mA);
        enable(ah, mB);

        // A 租户管理员调 /api/models 只看到 A
        String adminAUsername = createTenantAdmin(ah, tA);
        HttpHeaders aH = login(adminAUsername, "TaPass2026!");
        ResponseEntity<String> rA = restTemplate.exchange(base + "/api/models",
                HttpMethod.GET, new HttpEntity<>(aH), String.class);
        assertThat(rA.getStatusCode()).isEqualTo(HttpStatus.OK);
        JsonNode itemsA = om.readTree(rA.getBody()).get("data");
        assertThat(itemsA.size()).isEqualTo(1);
        assertThat(itemsA.get(0).get("id").asLong()).isEqualTo(mA);

        // B 租户管理员只看到 B
        HttpHeaders bH = login(adminB, "TaPass2026!");
        ResponseEntity<String> rB = restTemplate.exchange(base + "/api/models",
                HttpMethod.GET, new HttpEntity<>(bH), String.class);
        JsonNode itemsB = om.readTree(rB.getBody()).get("data");
        assertThat(itemsB.size()).isEqualTo(1);
        assertThat(itemsB.get(0).get("id").asLong()).isEqualTo(mB);

        // 公开目录绝不返回内部字段
        String body = rA.getBody();
        assertThat(body).doesNotContain("tenantId", "providerChannelId", "providerChannel",
                "upstreamModelId", "candidate", "mapping", "route", "credential",
                "publishStatus", "deletedAt", "version");
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

    // ---------- 帮助方法 ----------

    private void publishPrice(HttpHeaders ah, long modelId, String input, String output) throws Exception {
        String body = om.writeValueAsString(Map.of(
                "inputPricePerMillion", input,
                "outputPricePerMillion", output));
        ResponseEntity<String> r = restTemplate.exchange(base + "/api/tenant-models/" + modelId + "/prices",
                HttpMethod.POST, new HttpEntity<>(body, ah), String.class);
        assertThat(r.getStatusCode()).as("价格发布失败: %s", r.getBody()).isEqualTo(HttpStatus.OK);
    }

    private void enable(HttpHeaders ah, long modelId) throws Exception {
        ResponseEntity<String> r = restTemplate.exchange(base + "/api/tenant-models/" + modelId + "/enable",
                HttpMethod.POST, new HttpEntity<>(ah), String.class);
        assertThat(r.getStatusCode()).as("启用失败: %s", r.getBody()).isEqualTo(HttpStatus.OK);
    }
}
