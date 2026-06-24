package io.fluxora.platform.upstream;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;

import javax.sql.DataSource;

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
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.web.client.DefaultResponseErrorHandler;
import org.springframework.web.client.RestTemplate;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 上游配置控制面集成测试。
 *
 * 覆盖需求 §十二 的 20 项后端安全约束：共享/私有隔离、同 URL 多协议、URL 规范化、
 * 凭证加密不回显、替换、软删除重导入、停用凭证跳过、批内去重、并发唯一约束、
 * 引用保护与越权拒绝。
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Testcontainers
class UpstreamIntegrationTest {

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

    public UpstreamIntegrationTest() {
        restTemplate = new RestTemplate();
        restTemplate.setErrorHandler(new DefaultResponseErrorHandler() {
            @Override public boolean hasError(HttpStatusCode s) { return false; }
        });
    }

    @BeforeEach void setUp() { base = "http://localhost:" + port; }

    // ---------- 辅助 ----------

    private HttpHeaders login(String u, String p) throws Exception {
        String json = om.writeValueAsString(Map.of("username", u, "password", p));
        HttpHeaders h = new HttpHeaders(); h.set("Content-Type", "application/json");
        ResponseEntity<String> r = restTemplate.exchange(base + "/api/auth/login", HttpMethod.POST, new HttpEntity<>(json, h), String.class);
        assertThat(r.getStatusCode()).isEqualTo(HttpStatus.OK);
        HttpHeaders ah = new HttpHeaders();
        ah.put(HttpHeaders.COOKIE, r.getHeaders().get(HttpHeaders.SET_COOKIE));
        ah.set("Content-Type", "application/json");
        return ah;
    }

    private HttpHeaders adminAuth() throws Exception { return login("admin", "Admin@2026!"); }

    /** 确保 default 自营租户已初始化（幂等，重复调用不会报错），返回其租户 ID。 */
    private long ensureDefaultTenant(HttpHeaders ah) throws Exception {
        String ij = om.writeValueAsString(Map.of("tenantName", "自营", "adminUsername", "seed_" + System.nanoTime(),
                "adminPassword", "TaPass2026!", "adminDisplayName", "Seed"));
        try {
            restTemplate.exchange(base + "/api/tenant/self-operated/initialize", HttpMethod.POST, new HttpEntity<>(ij, ah), String.class);
        } catch (Exception ignored) {
            // 已初始化则忽略
        }
        return defaultTenantId(ah);
    }

    /** 在 default 租户内创建一个租户管理员（通过成员接口，可重复创建），返回用户名。 */
    private String createDefaultTenantAdmin(HttpHeaders ah) throws Exception {
        return createTenantAdmin(ah, ensureDefaultTenant(ah));
    }

    private long defaultTenantId(HttpHeaders ah) throws Exception {
        ResponseEntity<String> list = restTemplate.exchange(base + "/api/tenant?keyword=default", HttpMethod.GET, new HttpEntity<>(ah), String.class);
        for (JsonNode n : om.readTree(list.getBody()).get("data").get("items")) {
            if ("default".equals(n.get("tenantCode").asText())) return n.get("id").asLong();
        }
        throw new IllegalStateException("default 租户不存在");
    }

    private long createTenant(HttpHeaders ah, String code) throws Exception {
        String j = om.writeValueAsString(Map.of("tenantCode", code, "name", "测试租户", "type", "STANDARD", "enabled", true));
        ResponseEntity<String> r = restTemplate.exchange(base + "/api/tenant", HttpMethod.POST, new HttpEntity<>(j, ah), String.class);
        return om.readTree(r.getBody()).get("data").get("id").asLong();
    }

    /** 在指定租户内创建一个租户管理员，返回用户名（密码统一 TaPass2026!）。 */
    private String createTenantAdmin(HttpHeaders ah, long tenantId) throws Exception {
        String username = "ta" + System.nanoTime();
        String j = om.writeValueAsString(Map.of("username", username, "displayName", username,
                "password", "TaPass2026!", "roleCode", "TENANT_ADMIN"));
        ResponseEntity<String> r = restTemplate.exchange(base + "/api/tenant/" + tenantId + "/members",
                HttpMethod.POST, new HttpEntity<>(j, ah), String.class);
        assertThat(r.getStatusCode()).isEqualTo(HttpStatus.OK);
        return username;
    }

    private String createTenantMember(HttpHeaders ah, long tenantId) throws Exception {
        String username = "u" + System.nanoTime();
        String j = om.writeValueAsString(Map.of("username", username, "displayName", username,
                "password", "Passw0rd!", "roleCode", "TENANT_MEMBER"));
        restTemplate.exchange(base + "/api/tenant/" + tenantId + "/members", HttpMethod.POST, new HttpEntity<>(j, ah), String.class);
        return username;
    }

    private long createProvider(HttpHeaders ah, String name, String code, String scopeType, Long tenantId) throws Exception {
        Map<String, Object> body = new java.util.HashMap<>(Map.of("name", name, "code", code, "scopeType", scopeType, "enabled", true));
        if (tenantId != null) body.put("tenantId", tenantId);
        String j = om.writeValueAsString(body);
        ResponseEntity<String> r = restTemplate.exchange(base + "/api/providers", HttpMethod.POST, new HttpEntity<>(j, ah), String.class);
        return om.readTree(r.getBody()).get("data").get("id").asLong();
    }

    private long createBaseUrl(HttpHeaders ah, long providerId, String protocol, String url) throws Exception {
        String j = om.writeValueAsString(Map.of("providerId", providerId, "protocol", protocol, "baseUrl", url));
        ResponseEntity<String> r = restTemplate.exchange(base + "/api/provider-base-urls", HttpMethod.POST, new HttpEntity<>(j, ah), String.class);
        return om.readTree(r.getBody()).get("data").get("id").asLong();
    }

    private long createChannel(HttpHeaders ah, Long tenantId, long baseUrlId, String name) throws Exception {
        Map<String, Object> body = new java.util.HashMap<>(Map.of(
                "providerBaseUrlId", baseUrlId, "name", name, "enabled", true,
                "priority", 100, "weight", 100, "connectTimeoutMs", 5000, "readTimeoutMs", 60000));
        if (tenantId != null) body.put("tenantId", tenantId);
        String j = om.writeValueAsString(body);
        ResponseEntity<String> r = restTemplate.exchange(base + "/api/provider-channels", HttpMethod.POST, new HttpEntity<>(j, ah), String.class);
        assertThat(r.getStatusCode()).isEqualTo(HttpStatus.OK);
        return om.readTree(r.getBody()).get("data").get("id").asLong();
    }

    private long createCredential(HttpHeaders ah, long channelId, String plaintext) throws Exception {
        String j = om.writeValueAsString(Map.of("providerChannelId", channelId, "plaintext", plaintext, "name", "c-" + System.nanoTime()));
        ResponseEntity<String> r = restTemplate.exchange(base + "/api/provider-credentials", HttpMethod.POST, new HttpEntity<>(j, ah), String.class);
        return om.readTree(r.getBody()).get("data").get("id").asLong();
    }

    @Test void credentialAuthenticationTypeMustBePersistedAsSafeMetadata() throws Exception {
        HttpHeaders ah = adminAuth();
        long tenantId = ensureDefaultTenant(ah);
        long providerId = createProvider(ah, "认证方式厂商", "auth-type-" + System.nanoTime(), "TENANT_PRIVATE", tenantId);
        long baseUrlId = createBaseUrl(ah, providerId, "OPENAI", "https://auth-type.example/v1");
        long channelId = createChannel(ah, tenantId, baseUrlId, "认证方式通道");
        String createJson = om.writeValueAsString(Map.of(
                "providerChannelId", channelId, "plaintext", "credential-auth-type-secret",
                "name", "认证方式凭证", "authType", "X_API_KEY"));

        ResponseEntity<String> created = restTemplate.exchange(base + "/api/provider-credentials", HttpMethod.POST,
                new HttpEntity<>(createJson, ah), String.class);
        JsonNode data = om.readTree(created.getBody()).get("data");
        long credentialId = data.get("id").asLong();
        assertThat(data.path("authType").asText()).isEqualTo("X_API_KEY");
        assertThat(created.getBody()).doesNotContain("credential-auth-type-secret");

        String updateJson = om.writeValueAsString(Map.of("name", "认证方式凭证", "priority", 100,
                "weight", 100, "authType", "BEARER"));
        ResponseEntity<String> updated = restTemplate.exchange(base + "/api/provider-credentials/" + credentialId,
                HttpMethod.PUT, new HttpEntity<>(updateJson, ah), String.class);
        assertThat(om.readTree(updated.getBody()).get("data").path("authType").asText()).isEqualTo("BEARER");
        Integer refreshEvents = new JdbcTemplate(dataSource).queryForObject("""
                SELECT COUNT(*) FROM runtime_outbox
                WHERE aggregate_type='PROVIDER_CREDENTIAL' AND aggregate_id=? AND mutation_type='AUTH_TYPE_CHANGED'
                """, Integer.class, credentialId);
        assertThat(refreshEvents).isEqualTo(1);

        String invalidJson = om.writeValueAsString(Map.of("name", "认证方式凭证", "priority", 100,
                "weight", 100, "authType", "BASIC"));
        ResponseEntity<String> invalid = restTemplate.exchange(base + "/api/provider-credentials/" + credentialId,
                HttpMethod.PUT, new HttpEntity<>(invalidJson, ah), String.class);
        assertThat(invalid.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(invalid.getBody()).doesNotContain("ProviderCredential", "exception", "BASIC");
    }

    private JsonNode importCredentials(HttpHeaders ah, long channelId, List<String> lines) throws Exception {
        String j = om.writeValueAsString(Map.of("providerChannelId", channelId, "lines", lines, "namePrefix", "批量"));
        ResponseEntity<String> r = restTemplate.exchange(base + "/api/provider-credentials/import", HttpMethod.POST, new HttpEntity<>(j, ah), String.class);
        return om.readTree(r.getBody()).get("data");
    }

    // ---------- 1. 平台管理员创建共享 Provider ----------

    @Test void platformAdminCanCreateSharedProvider() throws Exception {
        HttpHeaders ah = adminAuth();
        String j = om.writeValueAsString(Map.of("name", "OpenAI", "code", "openai-" + System.nanoTime(), "scopeType", "PLATFORM_SHARED", "enabled", true));
        ResponseEntity<String> r = restTemplate.exchange(base + "/api/providers", HttpMethod.POST, new HttpEntity<>(j, ah), String.class);
        assertThat(r.getStatusCode()).isEqualTo(HttpStatus.OK);
        JsonNode data = om.readTree(r.getBody()).get("data");
        assertThat(data.get("scopeType").asText()).isEqualTo("PLATFORM_SHARED");
        assertThat(data.get("status").asText()).isEqualTo("ENABLED");
        assertThat(data.has("deletedAt")).isFalse();
    }

    // ---------- 2. 租户管理员创建私有 Provider ----------

    @Test void tenantAdminCanCreatePrivateProvider() throws Exception {
        HttpHeaders ah = adminAuth();
        String ta = createDefaultTenantAdmin(ah);
        HttpHeaders tah = login(ta, "TaPass2026!");
        String j = om.writeValueAsString(Map.of("name", "私有", "code", "priv-" + System.nanoTime(), "scopeType", "TENANT_PRIVATE", "enabled", true));
        ResponseEntity<String> r = restTemplate.exchange(base + "/api/providers", HttpMethod.POST, new HttpEntity<>(j, tah), String.class);
        assertThat(r.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(om.readTree(r.getBody()).get("data").get("scopeType").asText()).isEqualTo("TENANT_PRIVATE");
    }

    // ---------- 3. 租户管理员可读但不可改共享 Provider ----------

    @Test void tenantAdminCannotUpdateSharedProvider() throws Exception {
        HttpHeaders ah = adminAuth();
        long pid = createProvider(ah, "Shared", "sh-" + System.nanoTime(), "PLATFORM_SHARED", null);
        String ta = createDefaultTenantAdmin(ah);
        HttpHeaders tah = login(ta, "TaPass2026!");

        // 可读
        ResponseEntity<String> get = restTemplate.exchange(base + "/api/providers/" + pid, HttpMethod.GET, new HttpEntity<>(tah), String.class);
        assertThat(get.getStatusCode()).isEqualTo(HttpStatus.OK);

        // 不可改
        String j = om.writeValueAsString(Map.of("name", "hacked", "description", "x"));
        ResponseEntity<String> upd = restTemplate.exchange(base + "/api/providers/" + pid, HttpMethod.PUT, new HttpEntity<>(j, tah), String.class);
        assertThat(upd.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(upd.getBody()).doesNotContain("403", "exception", "ProviderException");
    }

    // ---------- 4 & 5. 同 URL 多协议允许；同协议同 URL 拒绝 ----------

    @Test void sameUrlWithDifferentProtocolsAllowedButSameProtocolDuplicateRejected() throws Exception {
        HttpHeaders ah = adminAuth();
        long pid = createProvider(ah, "Multi", "mul-" + System.nanoTime(), "PLATFORM_SHARED", null);
        long b1 = createBaseUrl(ah, pid, "OPENAI", "https://api.example.com/v1");
        long b2 = createBaseUrl(ah, pid, "ANTHROPIC", "https://api.example.com/v1");
        assertThat(b1).isNotEqualTo(b2);

        // 同协议同规范化 URL 重复 → 400 安全文案
        String j = om.writeValueAsString(Map.of("providerId", pid, "protocol", "OPENAI", "baseUrl", "https://api.example.com/v1///"));
        ResponseEntity<String> r = restTemplate.exchange(base + "/api/provider-base-urls", HttpMethod.POST, new HttpEntity<>(j, ah), String.class);
        assertThat(r.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(r.getBody()).contains("相同协议和接入地址");
    }

    // ---------- URL 规范化与业务路径拒绝 ----------

    @Test void invalidBaseUrlReturnsSafeFieldMessage() throws Exception {
        HttpHeaders ah = adminAuth();
        long pid = createProvider(ah, "Url", "url-" + System.nanoTime(), "PLATFORM_SHARED", null);
        String j = om.writeValueAsString(Map.of("providerId", pid, "protocol", "OPENAI", "baseUrl", "https://api.example.com/v1/chat/completions"));
        ResponseEntity<String> r = restTemplate.exchange(base + "/api/provider-base-urls", HttpMethod.POST, new HttpEntity<>(j, ah), String.class);
        assertThat(r.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(r.getBody()).contains("接入基础地址");
        assertThat(r.getBody()).doesNotContain("chat/completions");
    }

    // ---------- 6. 租户不能引用其他租户私有 BaseUrl 创建通道 ----------

    @Test void tenantCannotReferenceOtherTenantPrivateBaseUrl() throws Exception {
        HttpHeaders ah = adminAuth();
        long defaultTid = defaultTenantId(ah);
        long t2 = createTenant(ah, "t2-" + System.nanoTime());
        String ta2 = createTenantAdmin(ah, t2);
        HttpHeaders ta2h = login(ta2, "TaPass2026!");

        // T2 私有 provider + baseurl
        long t2pid = createProvider(ah, "T2P", "t2p-" + System.nanoTime(), "TENANT_PRIVATE", t2);
        long t2url = createBaseUrl(ah, t2pid, "OPENAI", "https://t2.example.com/v1");

        // default 租户管理员尝试引用 T2 私有地址创建通道 → 拒绝
        String ta1 = createDefaultTenantAdmin(ah);
        HttpHeaders ta1h = login(ta1, "TaPass2026!");
        String j = om.writeValueAsString(Map.of("providerBaseUrlId", t2url, "name", "leak", "priority", 100, "weight", 100, "connectTimeoutMs", 5000, "readTimeoutMs", 60000));
        ResponseEntity<String> r = restTemplate.exchange(base + "/api/provider-channels", HttpMethod.POST, new HttpEntity<>(j, ta1h), String.class);
        assertThat(r.getStatusCode()).isIn(HttpStatus.BAD_REQUEST, HttpStatus.FORBIDDEN, HttpStatus.NOT_FOUND);
    }

    // ---------- 7. 租户管理员只能管理本租户通道与凭证 ----------

    @Test void tenantAdminCannotAccessOtherTenantChannel() throws Exception {
        HttpHeaders ah = adminAuth();
        long t2 = createTenant(ah, "iso-" + System.nanoTime());
        String ta2 = createTenantAdmin(ah, t2);
        HttpHeaders ta2h = login(ta2, "TaPass2026!");
        long pid = createProvider(ah, "Sh", "shiso-" + System.nanoTime(), "PLATFORM_SHARED", null);
        long url = createBaseUrl(ah, pid, "OPENAI", "https://sh.example.com/v1");
        long channel = createChannel(ta2h, t2, url, "T2通道");

        // default 租户管理员访问 T2 通道 → 403/404
        String ta1 = createDefaultTenantAdmin(ah);
        HttpHeaders ta1h = login(ta1, "TaPass2026!");
        ResponseEntity<String> r = restTemplate.exchange(base + "/api/provider-channels/" + channel, HttpMethod.GET, new HttpEntity<>(ta1h), String.class);
        assertThat(r.getStatusCode()).isIn(HttpStatus.FORBIDDEN, HttpStatus.NOT_FOUND);
    }

    // ---------- 8. 普通成员无法访问上游配置 ----------

    @Test void memberCannotAccessUpstream() throws Exception {
        HttpHeaders ah = adminAuth();
        long tid = ensureDefaultTenant(ah);
        String member = createTenantMember(ah, tid);
        HttpHeaders mh = login(member, "Passw0rd!");
        ResponseEntity<String> r = restTemplate.exchange(base + "/api/providers", HttpMethod.GET, new HttpEntity<>(mh), String.class);
        assertThat(r.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
    }

    // ---------- 9. 凭证明文不写入数据库 ----------

    @Test void plaintextNeverStoredInDatabase() throws Exception {
        HttpHeaders ah = adminAuth();
        long tid = defaultTenantId(ah);
        String ta = createTenantAdmin(ah, tid);
        HttpHeaders tah = login(ta, "TaPass2026!");
        long pid = createProvider(tah, "P", "pdb-" + System.nanoTime(), "TENANT_PRIVATE", null);
        long url = createBaseUrl(tah, pid, "OPENAI", "https://pdb.example.com/v1");
        long channel = createChannel(tah, null, url, "ch");
        String plaintext = "sk-PLAINTEXT-SECRET-12345";
        createCredential(tah, channel, plaintext);

        JdbcTemplate jdbc = new JdbcTemplate(dataSource);
        Integer plaintextCol = jdbc.queryForObject(
                "SELECT COUNT(*) FROM information_schema.columns WHERE table_name='provider_credential' "
                        + "AND column_name IN ('plaintext','full_credential','master_key')", Integer.class);
        assertThat(plaintextCol).isEqualTo(0);
        Integer leaked = jdbc.queryForObject(
                "SELECT COUNT(*) FROM provider_credential WHERE ciphertext LIKE ? OR masked_value LIKE ?",
                Integer.class, "%" + plaintext + "%", "%" + plaintext + "%");
        assertThat(leaked).isEqualTo(0);
    }

    // ---------- 10. 列表/详情不返回明文、密文、向量、指纹、加密版本 ----------

    @Test void responseNeverContainsSensitiveFields() throws Exception {
        HttpHeaders ah = adminAuth();
        long tid = defaultTenantId(ah);
        String ta = createTenantAdmin(ah, tid);
        HttpHeaders tah = login(ta, "TaPass2026!");
        long pid = createProvider(tah, "P", "sf-" + System.nanoTime(), "TENANT_PRIVATE", null);
        long url = createBaseUrl(tah, pid, "OPENAI", "https://sf.example.com/v1");
        long channel = createChannel(tah, null, url, "ch");
        long cid = createCredential(tah, channel, "sk-SUPERSECRET-VALUE-9999");

        ResponseEntity<String> det = restTemplate.exchange(base + "/api/provider-credentials/" + cid, HttpMethod.GET, new HttpEntity<>(tah), String.class);
        String detailBody = det.getBody();
        assertThat(detailBody).doesNotContain("ciphertext", "initializationVector", "credentialFingerprint", "encryptionVersion", "deletedAt", "sk-SUPERSECRET-VALUE-9999");

        ResponseEntity<String> list = restTemplate.exchange(base + "/api/provider-credentials?providerChannelId=" + channel, HttpMethod.GET, new HttpEntity<>(tah), String.class);
        assertThat(list.getBody()).doesNotContain("ciphertext", "credentialFingerprint", "sk-SUPERSECRET-VALUE-9999");
    }

    // ---------- 12. 替换凭证后旧密文不再被使用 ----------

    @Test void replaceCredentialUpdatesCiphertextAndMasked() throws Exception {
        HttpHeaders ah = adminAuth();
        long tid = defaultTenantId(ah);
        String ta = createTenantAdmin(ah, tid);
        HttpHeaders tah = login(ta, "TaPass2026!");
        long pid = createProvider(tah, "P", "rep-" + System.nanoTime(), "TENANT_PRIVATE", null);
        long url = createBaseUrl(tah, pid, "OPENAI", "https://rep.example.com/v1");
        long channel = createChannel(tah, null, url, "ch");
        long cid = createCredential(tah, channel, "sk-OLD-SECRET-AAAA");

        JdbcTemplate jdbc = new JdbcTemplate(dataSource);
        String oldCiphertext = jdbc.queryForObject("SELECT ciphertext FROM provider_credential WHERE id=?", String.class, cid);
        String oldMasked = jdbc.queryForObject("SELECT masked_value FROM provider_credential WHERE id=?", String.class, cid);

        String j = om.writeValueAsString(Map.of("plaintext", "sk-NEW-SECRET-BBBB"));
        restTemplate.exchange(base + "/api/provider-credentials/" + cid + "/replace", HttpMethod.PUT, new HttpEntity<>(j, tah), String.class);

        String newCiphertext = jdbc.queryForObject("SELECT ciphertext FROM provider_credential WHERE id=?", String.class, cid);
        String newMasked = jdbc.queryForObject("SELECT masked_value FROM provider_credential WHERE id=?", String.class, cid);
        assertThat(newCiphertext).isNotEqualTo(oldCiphertext);
        assertThat(newMasked).isNotEqualTo(oldMasked);
        assertThat(newMasked).doesNotContain("OLD");
    }

    // ---------- 13. 已启用重复凭证跳过 ----------

    @Test void importSkipsExistingEnabledCredential() throws Exception {
        HttpHeaders ah = adminAuth();
        long tid = defaultTenantId(ah);
        String ta = createTenantAdmin(ah, tid);
        HttpHeaders tah = login(ta, "TaPass2026!");
        long pid = createProvider(tah, "P", "imp1-" + System.nanoTime(), "TENANT_PRIVATE", null);
        long url = createBaseUrl(tah, pid, "OPENAI", "https://imp1.example.com/v1");
        long channel = createChannel(tah, null, url, "ch");
        createCredential(tah, channel, "sk-DUP-ENABLED-001");

        JsonNode result = importCredentials(tah, channel, List.of("sk-DUP-ENABLED-001"));
        assertThat(result.get("summary").get("imported").asInt()).isEqualTo(0);
        assertThat(result.get("summary").get("skippedExisting").asInt()).isEqualTo(1);
    }

    // ---------- 14. 已停用重复凭证跳过且不自动启用 ----------

    @Test void importSkipsDisabledCredentialWithoutEnabling() throws Exception {
        HttpHeaders ah = adminAuth();
        long tid = defaultTenantId(ah);
        String ta = createTenantAdmin(ah, tid);
        HttpHeaders tah = login(ta, "TaPass2026!");
        long pid = createProvider(tah, "P", "imp2-" + System.nanoTime(), "TENANT_PRIVATE", null);
        long url = createBaseUrl(tah, pid, "OPENAI", "https://imp2.example.com/v1");
        long channel = createChannel(tah, null, url, "ch");
        long cid = createCredential(tah, channel, "sk-DUP-DISABLED-002");

        // 停用
        restTemplate.exchange(base + "/api/provider-credentials/" + cid + "/disable", HttpMethod.PUT, new HttpEntity<>("{}", tah), String.class);

        JsonNode result = importCredentials(tah, channel, List.of("sk-DUP-DISABLED-002"));
        assertThat(result.get("summary").get("imported").asInt()).isEqualTo(0);
        assertThat(result.get("summary").get("skippedExisting").asInt()).isEqualTo(1);

        // 仍处于停用，未被自动启用
        JdbcTemplate jdbc = new JdbcTemplate(dataSource);
        Boolean enabled = jdbc.queryForObject("SELECT enabled FROM provider_credential WHERE id=?", Boolean.class, cid);
        assertThat(enabled).isFalse();
    }

    // ---------- 15. 软删除后可重新导入 ----------

    @Test void softDeletedCredentialCanBeImportedAgain() throws Exception {
        HttpHeaders ah = adminAuth();
        long tid = defaultTenantId(ah);
        String ta = createTenantAdmin(ah, tid);
        HttpHeaders tah = login(ta, "TaPass2026!");
        long pid = createProvider(tah, "P", "imp3-" + System.nanoTime(), "TENANT_PRIVATE", null);
        long url = createBaseUrl(tah, pid, "OPENAI", "https://imp3.example.com/v1");
        long channel = createChannel(tah, null, url, "ch");
        long cid = createCredential(tah, channel, "sk-REIMPORT-003");

        restTemplate.exchange(base + "/api/provider-credentials/" + cid, HttpMethod.DELETE, new HttpEntity<>(tah), String.class);

        JsonNode result = importCredentials(tah, channel, List.of("sk-REIMPORT-003"));
        assertThat(result.get("summary").get("imported").asInt()).isEqualTo(1);
    }

    // ---------- 16. 同批重复仅首次导入 ----------

    @Test void duplicateLinesInBatchOnlyImportFirst() throws Exception {
        HttpHeaders ah = adminAuth();
        long tid = defaultTenantId(ah);
        String ta = createTenantAdmin(ah, tid);
        HttpHeaders tah = login(ta, "TaPass2026!");
        long pid = createProvider(tah, "P", "imp4-" + System.nanoTime(), "TENANT_PRIVATE", null);
        long url = createBaseUrl(tah, pid, "OPENAI", "https://imp4.example.com/v1");
        long channel = createChannel(tah, null, url, "ch");

        JsonNode result = importCredentials(tah, channel, List.of("sk-BATCH-DUP-004", "sk-BATCH-DUP-004", "sk-BATCH-DUP-004"));
        assertThat(result.get("summary").get("imported").asInt()).isEqualTo(1);
        assertThat(result.get("summary").get("skippedBatchDuplicate").asInt()).isEqualTo(2);
    }

    // ---------- 17. 并发导入同一凭证最多一条写入 ----------

    @Test void concurrentImportAllowsAtMostOneActiveFingerprint() throws Exception {
        HttpHeaders ah = adminAuth();
        long tid = ensureDefaultTenant(ah);
        String ta = createTenantAdmin(ah, tid);
        HttpHeaders tah = login(ta, "TaPass2026!");
        long pid = createProvider(tah, "P", "conc-" + System.nanoTime(), "TENANT_PRIVATE", null);
        long url = createBaseUrl(tah, pid, "OPENAI", "https://conc.example.com/v1");
        long channel = createChannel(tah, null, url, "ch");

        int threads = 4;
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CountDownLatch start = new CountDownLatch(1);
        CountDownLatch done = new CountDownLatch(threads);
        AtomicInteger importedTotal = new AtomicInteger();
        AtomicInteger internalFailures = new AtomicInteger();
        for (int i = 0; i < threads; i++) {
            pool.submit(() -> {
                try {
                    start.await();
                    String request = om.writeValueAsString(Map.of("providerChannelId", channel,
                            "lines", List.of("sk-CONCURRENT-005"), "namePrefix", "并发"));
                    ResponseEntity<String> response = restTemplate.exchange(base + "/api/provider-credentials/import",
                            HttpMethod.POST, new HttpEntity<>(request, tah), String.class);
                    if (response.getStatusCode().is2xxSuccessful()) {
                        importedTotal.addAndGet(om.readTree(response.getBody()).get("data").get("summary").get("imported").asInt());
                    } else {
                        internalFailures.incrementAndGet();
                    }
                } catch (Exception error) {
                    internalFailures.incrementAndGet();
                } finally {
                    done.countDown();
                }
            });
        }
        start.countDown();
        done.await();
        pool.shutdownNow();

        // 四个并发请求中，最多一个真正写入；其余跳过（已存在或并发）
        assertThat(importedTotal.get()).isLessThanOrEqualTo(1);
        assertThat(internalFailures.get()).isZero();
        JdbcTemplate jdbc = new JdbcTemplate(dataSource);
        Integer active = jdbc.queryForObject(
                "SELECT COUNT(*) FROM provider_channel_credential WHERE provider_channel_id=? AND deleted_at IS NULL AND enabled = TRUE",
                Integer.class, channel);
        assertThat(active).isEqualTo(1);
    }

    // ---------- 18. 导入结果准确返回数量与原因 ----------

    @Test void importResultReturnsAccurateCountsAndReasons() throws Exception {
        HttpHeaders ah = adminAuth();
        long tid = defaultTenantId(ah);
        String ta = createTenantAdmin(ah, tid);
        HttpHeaders tah = login(ta, "TaPass2026!");
        long pid = createProvider(tah, "P", "acc-" + System.nanoTime(), "TENANT_PRIVATE", null);
        long url = createBaseUrl(tah, pid, "OPENAI", "https://acc.example.com/v1");
        long channel = createChannel(tah, null, url, "ch");
        createCredential(tah, channel, "sk-ACC-EXISTING");

        JsonNode result = importCredentials(tah, channel, List.of(
                "sk-ACC-NEW-1", "sk-ACC-EXISTING", "sk-ACC-NEW-1", "   ", "sk-ACC-NEW-2"));
        JsonNode s = result.get("summary");
        assertThat(s.get("imported").asInt()).isEqualTo(2);
        assertThat(s.get("skippedExisting").asInt()).isEqualTo(1);
        assertThat(s.get("skippedBatchDuplicate").asInt()).isEqualTo(1);
        assertThat(s.get("invalid").asInt()).isEqualTo(1);
        // 明细包含行号与脱敏标识
        assertThat(result.get("items").size()).isGreaterThan(0);
    }

    // ---------- 19. 导入结果不泄露完整凭证 ----------

    @Test void importResultDoesNotLeakPlaintext() throws Exception {
        HttpHeaders ah = adminAuth();
        long tid = defaultTenantId(ah);
        String ta = createTenantAdmin(ah, tid);
        HttpHeaders tah = login(ta, "TaPass2026!");
        long pid = createProvider(tah, "P", "leak-" + System.nanoTime(), "TENANT_PRIVATE", null);
        long url = createBaseUrl(tah, pid, "OPENAI", "https://leak.example.com/v1");
        long channel = createChannel(tah, null, url, "ch");

        String secret = "sk-LEAK-SECRET-CHECK-VALUE";
        ResponseEntity<String> r = restTemplate.exchange(base + "/api/provider-credentials/import",
                HttpMethod.POST, new HttpEntity<>(om.writeValueAsString(Map.of("providerChannelId", channel, "lines", List.of(secret), "namePrefix", "批量")), tah), String.class);
        assertThat(r.getBody()).doesNotContain(secret);
    }

    // ---------- 20. 引用保护：被引用的 Provider/BaseUrl 不可删除 ----------

    @Test void referencedProviderAndBaseUrlCannotBeDeleted() throws Exception {
        HttpHeaders ah = adminAuth();
        long tid = defaultTenantId(ah);
        String ta = createTenantAdmin(ah, tid);
        HttpHeaders tah = login(ta, "TaPass2026!");
        long pid = createProvider(tah, "P", "ref-" + System.nanoTime(), "TENANT_PRIVATE", null);
        long url = createBaseUrl(tah, pid, "OPENAI", "https://ref.example.com/v1");

        // Provider 被地址引用 → 不可删
        ResponseEntity<String> dp = restTemplate.exchange(base + "/api/providers/" + pid, HttpMethod.DELETE, new HttpEntity<>(tah), String.class);
        assertThat(dp.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(dp.getBody()).contains("关联配置");

        createChannel(tah, null, url, "ch");
        // BaseUrl 被通道引用 → 不可删
        ResponseEntity<String> db = restTemplate.exchange(base + "/api/provider-base-urls/" + url, HttpMethod.DELETE, new HttpEntity<>(tah), String.class);
        assertThat(db.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(db.getBody()).contains("通道");
    }

    // ---------- 平台管理员可跨租户管理通道 ----------

    @Test void platformAdminCanCrossTenantManageChannel() throws Exception {
        HttpHeaders ah = adminAuth();
        long t2 = createTenant(ah, "cross-" + System.nanoTime());
        long pid = createProvider(ah, "Sh", "shcross-" + System.nanoTime(), "PLATFORM_SHARED", null);
        long url = createBaseUrl(ah, pid, "OPENAI", "https://cross.example.com/v1");
        long channel = createChannel(ah, t2, url, "T2通道");

        ResponseEntity<String> list = restTemplate.exchange(base + "/api/provider-channels?tenantId=" + t2, HttpMethod.GET, new HttpEntity<>(ah), String.class);
        assertThat(list.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(om.readTree(list.getBody()).get("data").get("total").asLong()).isGreaterThanOrEqualTo(1);

        // 平台管理员删除 T2 通道
        ResponseEntity<String> del = restTemplate.exchange(base + "/api/provider-channels/" + channel, HttpMethod.DELETE, new HttpEntity<>(ah), String.class);
        assertThat(del.getStatusCode()).isEqualTo(HttpStatus.OK);
    }

    // ---------- 统计接口可用 ----------

    @Test void statsEndpointsReturnAggregates() throws Exception {
        HttpHeaders ah = adminAuth();
        ResponseEntity<String> ps = restTemplate.exchange(base + "/api/providers/stats", HttpMethod.GET, new HttpEntity<>(ah), String.class);
        assertThat(ps.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(om.readTree(ps.getBody()).get("data").has("total")).isTrue();
    }
}
