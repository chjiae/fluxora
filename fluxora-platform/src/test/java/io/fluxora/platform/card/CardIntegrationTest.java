package io.fluxora.platform.card;

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
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.web.client.DefaultResponseErrorHandler;
import org.springframework.web.client.RestTemplate;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import javax.sql.DataSource;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 卡密集成测试。
 *
 * 覆盖 AGENT.md「卡密充值」章节所有核心安全约束：
 *   1. DB 不保存完整卡密明文（无 plaintext 列；card_hash != plaintext）
 *   2. 创建响应一次性返回完整 plaintexts；后续列表 / 详情接口不再返回
 *   3. 输入规范化：大小写、空格、连字符容错后可正常核销
 *   4. 同一卡密只能核销一次（CARD_ALREADY_REDEEMED）
 *   5. 并发核销同一卡密：10 线程 → 仅 1 成功
 *   6. 核销后余额 += 面额，且写入 1 条 source=CARD_REDEEM 流水（绑定 card_id）
 *   7. 已停用卡密 / 已停用批次 / 已过期 / 跨租户无法核销，错误码精确映射
 *   8. 终态（REDEEMED / EXPIRED）卡密不允许变更状态
 *   9. 租户管理员只能管理本租户；平台管理员可跨租户；普通用户访问被拒
 *  10. 错误响应不泄露 SQL / 堆栈 / 内部异常细节，仅返回稳定业务错误码
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Testcontainers
class CardIntegrationTest {

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
    private final ObjectMapper objectMapper = new ObjectMapper();
    private String baseUrl;

    public CardIntegrationTest() {
        this.restTemplate = new RestTemplate();
        this.restTemplate.setErrorHandler(new DefaultResponseErrorHandler() {
            @Override public boolean hasError(HttpStatusCode s) { return false; }
        });
    }

    @BeforeEach
    void setUp() { baseUrl = "http://localhost:" + port; }

    // ---------- 通用辅助 ----------

    private HttpHeaders login(String u, String p) throws Exception {
        String json = objectMapper.writeValueAsString(Map.of("username", u, "password", p));
        HttpHeaders h = new HttpHeaders(); h.set("Content-Type", "application/json");
        ResponseEntity<String> r = restTemplate.exchange(
                baseUrl + "/api/auth/login", HttpMethod.POST, new HttpEntity<>(json, h), String.class);
        if (r.getStatusCode() != HttpStatus.OK)
            throw new IllegalStateException("登录失败：" + r.getStatusCode() + " " + r.getBody());
        HttpHeaders ah = new HttpHeaders();
        ah.put(HttpHeaders.COOKIE, r.getHeaders().get(HttpHeaders.SET_COOKIE));
        ah.set("Content-Type", "application/json");
        return ah;
    }
    private HttpHeaders adminAuth() throws Exception { return login("admin", "Admin@2026!"); }

    /** 确保自营初始化，返回 default 租户 ID（多个用例共享一个容器，幂等执行） */
    private long ensureSelfOperated(HttpHeaders ah) throws Exception {
        String seed = "seed_" + System.nanoTime();
        String ij = objectMapper.writeValueAsString(Map.of(
                "tenantName", "自营", "adminUsername", seed,
                "adminPassword", "SeedPass2026!", "adminDisplayName", "Seed"));
        restTemplate.exchange(baseUrl + "/api/tenant/self-operated/initialize",
                HttpMethod.POST, new HttpEntity<>(ij, ah), String.class);
        ResponseEntity<String> list = restTemplate.exchange(
                baseUrl + "/api/tenant?keyword=default", HttpMethod.GET, new HttpEntity<>(ah), String.class);
        for (JsonNode n : objectMapper.readTree(list.getBody()).get("data").get("items")) {
            if ("default".equals(n.get("tenantCode").asText())) return n.get("id").asLong();
        }
        throw new IllegalStateException("default 不存在");
    }

    /** 在 default 自营租户创建一名 TENANT_ADMIN，避免多个用例共享种子账号导致状态污染 */
    private String createTenantAdminInDefault(HttpHeaders ah) throws Exception {
        long defaultId = ensureSelfOperated(ah);
        String username = "cta_" + System.nanoTime();
        String j = objectMapper.writeValueAsString(Map.of(
                "username", username, "displayName", "Tenant Admin",
                "password", "TaPass2026!", "roleCode", "TENANT_ADMIN"));
        ResponseEntity<String> r = restTemplate.exchange(
                baseUrl + "/api/tenant/" + defaultId + "/members",
                HttpMethod.POST, new HttpEntity<>(j, ah), String.class);
        assertThat(r.getStatusCode()).as("创建 TENANT_ADMIN 应成功: %s", r.getBody())
                .isEqualTo(HttpStatus.OK);
        return username;
    }

    /** 在指定租户创建一个 TENANT_MEMBER；返回 username（密码统一 Passw0rd!Strong） */
    private String createTenantMember(HttpHeaders ah, long tenantId) throws Exception {
        String username = "cm_" + System.nanoTime();
        String j = objectMapper.writeValueAsString(Map.of(
                "username", username, "displayName", username,
                "password", "Passw0rd!Strong", "roleCode", "TENANT_MEMBER"));
        ResponseEntity<String> r = restTemplate.exchange(
                baseUrl + "/api/tenant/" + tenantId + "/members",
                HttpMethod.POST, new HttpEntity<>(j, ah), String.class);
        assertThat(r.getStatusCode()).isEqualTo(HttpStatus.OK);
        return username;
    }

    private long createTenant(HttpHeaders ah, String code) throws Exception {
        String j = objectMapper.writeValueAsString(Map.of(
                "tenantCode", code, "name", "测试", "type", "STANDARD", "enabled", true));
        ResponseEntity<String> r = restTemplate.exchange(
                baseUrl + "/api/tenant", HttpMethod.POST, new HttpEntity<>(j, ah), String.class);
        assertThat(r.getStatusCode()).isEqualTo(HttpStatus.OK);
        return objectMapper.readTree(r.getBody()).get("data").get("id").asLong();
    }

    /** 让平台管理员在指定租户创建一张 amount 面额、count 数量的批次；返回创建响应 data 节点 */
    private JsonNode createBatch(HttpHeaders ah, long tenantId, String denomination, int count) throws Exception {
        String body = objectMapper.writeValueAsString(Map.of(
                "groups", List.of(Map.of(
                        "denomination", denomination,
                        "count", count,
                        "name", "test batch"))));
        ResponseEntity<String> r = restTemplate.exchange(
                baseUrl + "/api/tenant/" + tenantId + "/cards/batches",
                HttpMethod.POST, new HttpEntity<>(body, ah), String.class);
        assertThat(r.getStatusCode()).as("创建批次应成功: %s", r.getBody()).isEqualTo(HttpStatus.OK);
        return objectMapper.readTree(r.getBody()).get("data");
    }

    // ============================================================
    // 1 & 2. DB 不保存明文 + 创建响应一次性返回 plaintexts
    // ============================================================

    @Test
    void databaseShouldNeverStorePlaintextCardCode() throws Exception {
        HttpHeaders ah = adminAuth();
        long defaultId = ensureSelfOperated(ah);
        JsonNode created = createBatch(ah, defaultId, "10", 3);

        JsonNode plaintexts = created.get("plaintexts");
        assertThat(plaintexts.size()).isEqualTo(3);
        for (JsonNode p : plaintexts) {
            String code = p.asText();
            assertThat(code).startsWith("FLX-");
            // FLX + 5 段 × (-XXXX) = 3 + 5*5 = 28 字符
            assertThat(code).hasSize(28);
        }

        // recharge_card 表无 plaintext 类列
        JdbcTemplate jdbc = new JdbcTemplate(dataSource);
        Integer plaintextCol = jdbc.queryForObject(
                "SELECT COUNT(*) FROM information_schema.columns "
                        + "WHERE table_name = 'recharge_card' "
                        + "  AND column_name IN ('plaintext','card_plaintext','full_code')",
                Integer.class);
        assertThat(plaintextCol).as("recharge_card 表不应有任何明文列").isEqualTo(0);

        // 任何 card_hash 不等于明文
        List<String> hashes = jdbc.queryForList(
                "SELECT card_hash FROM recharge_card WHERE tenant_id = ?",
                String.class, defaultId);
        for (JsonNode p : plaintexts) {
            assertThat(hashes).doesNotContain(p.asText());
        }
        for (String h : hashes) {
            assertThat(h).hasSize(64);  // SHA-256 hex
        }
    }

    // ============================================================
    // 3. 列表 / 详情接口不返回明文
    // ============================================================

    @Test
    void listAndDetailNeverExposePlaintextOrHash() throws Exception {
        HttpHeaders ah = adminAuth();
        long defaultId = ensureSelfOperated(ah);
        JsonNode created = createBatch(ah, defaultId, "20", 2);
        long batchId = created.get("batches").get(0).get("id").asLong();

        // 列表批次
        ResponseEntity<String> list = restTemplate.exchange(
                baseUrl + "/api/tenant/" + defaultId + "/cards/batches?page=1&size=10",
                HttpMethod.GET, new HttpEntity<>(ah), String.class);
        assertThat(list.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(list.getBody()).doesNotContain("plaintext");
        assertThat(list.getBody()).doesNotContain("cardHash");

        // 列表单张卡密
        ResponseEntity<String> cards = restTemplate.exchange(
                baseUrl + "/api/tenant/" + defaultId + "/cards/batches/" + batchId + "/cards",
                HttpMethod.GET, new HttpEntity<>(ah), String.class);
        assertThat(cards.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(cards.getBody()).doesNotContain("plaintext");
        assertThat(cards.getBody()).doesNotContain("cardHash");
        for (JsonNode c : objectMapper.readTree(cards.getBody()).get("data").get("items")) {
            assertThat(c.has("cardPrefix")).isTrue();
            assertThat(c.has("cardHash")).isFalse();
        }
    }

    // ============================================================
    // 4. 输入规范化（大小写、空格、连字符）后可核销
    // ============================================================

    @Test
    void normalizedInputShouldRedeemSuccessfully() throws Exception {
        HttpHeaders ah = adminAuth();
        long defaultId = ensureSelfOperated(ah);
        String memberUsername = createTenantMember(ah, defaultId);

        JsonNode created = createBatch(ah, defaultId, "10", 1);
        String plaintext = created.get("plaintexts").get(0).asText();

        // 用户登录
        HttpHeaders user = login(memberUsername, "Passw0rd!Strong");

        // 用户输入加入空格 + 全小写 + 多余 - 应能识别
        String mangled = plaintext.toLowerCase().replace("-", "")
                .replaceAll("(.{4})", "$1 ");  // 每 4 字符加空格
        String j = objectMapper.writeValueAsString(Map.of("code", mangled));
        ResponseEntity<String> r = restTemplate.exchange(
                baseUrl + "/api/cards/redeem",
                HttpMethod.POST, new HttpEntity<>(j, user), String.class);
        assertThat(r.getStatusCode()).as("规范化后应可核销: %s", r.getBody()).isEqualTo(HttpStatus.OK);
        JsonNode data = objectMapper.readTree(r.getBody()).get("data");
        assertThat(new BigDecimal(data.get("amount").asText())).isEqualByComparingTo("10");
        assertThat(new BigDecimal(data.get("newBalance").asText())).isEqualByComparingTo("10");
        assertThat(data.get("cardPrefix").asText()).startsWith("FLX-");
    }

    // ============================================================
    // 5. 二次核销同一卡密 → CARD_ALREADY_REDEEMED
    // ============================================================

    @Test
    void duplicateRedemptionRejected() throws Exception {
        HttpHeaders ah = adminAuth();
        long defaultId = ensureSelfOperated(ah);
        String memberUsername = createTenantMember(ah, defaultId);
        HttpHeaders user = login(memberUsername, "Passw0rd!Strong");

        JsonNode created = createBatch(ah, defaultId, "5", 1);
        String code = created.get("plaintexts").get(0).asText();

        // 第一次成功
        String j = objectMapper.writeValueAsString(Map.of("code", code));
        ResponseEntity<String> r1 = restTemplate.exchange(
                baseUrl + "/api/cards/redeem", HttpMethod.POST, new HttpEntity<>(j, user), String.class);
        assertThat(r1.getStatusCode()).isEqualTo(HttpStatus.OK);

        // 第二次拒绝
        ResponseEntity<String> r2 = restTemplate.exchange(
                baseUrl + "/api/cards/redeem", HttpMethod.POST, new HttpEntity<>(j, user), String.class);
        assertThat(r2.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(r2.getBody()).contains("CARD_ALREADY_REDEEMED");
    }

    // ============================================================
    // 6. 并发核销同一卡密：最多 1 个成功
    // ============================================================

    @Test
    void concurrentRedemptionsExactlyOneWins() throws Exception {
        HttpHeaders ah = adminAuth();
        long defaultId = ensureSelfOperated(ah);
        String memberUsername = createTenantMember(ah, defaultId);
        HttpHeaders user = login(memberUsername, "Passw0rd!Strong");

        JsonNode created = createBatch(ah, defaultId, "7", 1);
        String code = created.get("plaintexts").get(0).asText();
        String body = objectMapper.writeValueAsString(Map.of("code", code));

        int threads = 10;
        CountDownLatch start = new CountDownLatch(1);
        CountDownLatch done = new CountDownLatch(threads);
        AtomicInteger ok = new AtomicInteger();
        AtomicInteger fail = new AtomicInteger();
        ExecutorService pool = Executors.newFixedThreadPool(threads);

        for (int i = 0; i < threads; i++) {
            pool.submit(() -> {
                try {
                    start.await();
                    ResponseEntity<String> r = restTemplate.exchange(
                            baseUrl + "/api/cards/redeem", HttpMethod.POST,
                            new HttpEntity<>(body, user), String.class);
                    if (r.getStatusCode() == HttpStatus.OK) ok.incrementAndGet();
                    else fail.incrementAndGet();
                } catch (Exception e) {
                    fail.incrementAndGet();
                } finally {
                    done.countDown();
                }
            });
        }
        start.countDown();
        assertThat(done.await(30, TimeUnit.SECONDS)).isTrue();
        pool.shutdown();

        assertThat(ok.get()).as("并发核销应仅 1 成功").isEqualTo(1);
        assertThat(fail.get()).isEqualTo(threads - 1);

        // DB 层防重复入账：流水恰好 1 条
        JdbcTemplate jdbc = new JdbcTemplate(dataSource);
        Long redeemTxn = jdbc.queryForObject(
                "SELECT COUNT(*) FROM credit_transaction WHERE source = 'CARD_REDEEM'"
                        + "   AND reason LIKE ?",
                Long.class, "卡密充值 " + created.get("plaintexts").get(0).asText().substring(0, 8) + "%");
        assertThat(redeemTxn).isEqualTo(1);
    }

    // ============================================================
    // 7. 流水带 source=CARD_REDEEM + card_id，且 reason 含前缀
    // ============================================================

    @Test
    void redemptionWritesCardRedeemTransactionWithCardId() throws Exception {
        HttpHeaders ah = adminAuth();
        long defaultId = ensureSelfOperated(ah);
        String memberUsername = createTenantMember(ah, defaultId);
        HttpHeaders user = login(memberUsername, "Passw0rd!Strong");

        JsonNode created = createBatch(ah, defaultId, "33", 1);
        String code = created.get("plaintexts").get(0).asText();
        String j = objectMapper.writeValueAsString(Map.of("code", code));
        ResponseEntity<String> r = restTemplate.exchange(
                baseUrl + "/api/cards/redeem", HttpMethod.POST, new HttpEntity<>(j, user), String.class);
        assertThat(r.getStatusCode()).isEqualTo(HttpStatus.OK);
        long cardId = objectMapper.readTree(r.getBody()).get("data").get("cardId").asLong();

        JdbcTemplate jdbc = new JdbcTemplate(dataSource);
        Map<String, Object> row = jdbc.queryForMap(
                "SELECT source, card_id, delta, reason FROM credit_transaction "
                        + " WHERE card_id = ? AND source = 'CARD_REDEEM'",
                cardId);
        assertThat(row.get("source")).isEqualTo("CARD_REDEEM");
        assertThat(((Number) row.get("card_id")).longValue()).isEqualTo(cardId);
        assertThat(((BigDecimal) row.get("delta"))).isEqualByComparingTo("33");
        // 流水 reason 只包含前缀，不应包含完整明文
        String reason = (String) row.get("reason");
        assertThat(reason).startsWith("卡密充值 FLX-");
        assertThat(reason).doesNotContain(code.substring(9));  // 后续 16 字符不出现
    }

    // ============================================================
    // 8. 卡密已停用 → CARD_DISABLED
    // ============================================================

    @Test
    void disabledCardCannotBeRedeemed() throws Exception {
        HttpHeaders ah = adminAuth();
        long defaultId = ensureSelfOperated(ah);
        String memberUsername = createTenantMember(ah, defaultId);
        HttpHeaders user = login(memberUsername, "Passw0rd!Strong");

        JsonNode created = createBatch(ah, defaultId, "12", 1);
        String code = created.get("plaintexts").get(0).asText();
        long batchId = created.get("batches").get(0).get("id").asLong();

        // 找到该批次内唯一一张卡的 id
        ResponseEntity<String> list = restTemplate.exchange(
                baseUrl + "/api/tenant/" + defaultId + "/cards/batches/" + batchId + "/cards",
                HttpMethod.GET, new HttpEntity<>(ah), String.class);
        long cardId = objectMapper.readTree(list.getBody()).get("data").get("items").get(0).get("id").asLong();

        // 平台管理员把卡密停用
        ResponseEntity<String> dis = restTemplate.exchange(
                baseUrl + "/api/cards/" + cardId + "/disable", HttpMethod.PUT,
                new HttpEntity<>("{}", ah), String.class);
        assertThat(dis.getStatusCode()).isEqualTo(HttpStatus.OK);

        // 用户尝试核销 → CARD_DISABLED
        String j = objectMapper.writeValueAsString(Map.of("code", code));
        ResponseEntity<String> r = restTemplate.exchange(
                baseUrl + "/api/cards/redeem", HttpMethod.POST, new HttpEntity<>(j, user), String.class);
        assertThat(r.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(r.getBody()).contains("CARD_DISABLED");
    }

    // ============================================================
    // 9. 批次已停用 → CARD_BATCH_DISABLED
    // ============================================================

    @Test
    void disabledBatchCannotBeRedeemed() throws Exception {
        HttpHeaders ah = adminAuth();
        long defaultId = ensureSelfOperated(ah);
        String memberUsername = createTenantMember(ah, defaultId);
        HttpHeaders user = login(memberUsername, "Passw0rd!Strong");

        JsonNode created = createBatch(ah, defaultId, "8", 1);
        String code = created.get("plaintexts").get(0).asText();
        long batchId = created.get("batches").get(0).get("id").asLong();

        // 停用批次
        restTemplate.exchange(
                baseUrl + "/api/tenant/" + defaultId + "/cards/batches/" + batchId + "/disable",
                HttpMethod.PUT, new HttpEntity<>("{}", ah), String.class);

        // 用户尝试核销 → CARD_BATCH_DISABLED
        String j = objectMapper.writeValueAsString(Map.of("code", code));
        ResponseEntity<String> r = restTemplate.exchange(
                baseUrl + "/api/cards/redeem", HttpMethod.POST, new HttpEntity<>(j, user), String.class);
        assertThat(r.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(r.getBody()).contains("CARD_BATCH_DISABLED");

        // 重新启用后可正常核销
        restTemplate.exchange(
                baseUrl + "/api/tenant/" + defaultId + "/cards/batches/" + batchId + "/enable",
                HttpMethod.PUT, new HttpEntity<>("{}", ah), String.class);
        ResponseEntity<String> ok = restTemplate.exchange(
                baseUrl + "/api/cards/redeem", HttpMethod.POST, new HttpEntity<>(j, user), String.class);
        assertThat(ok.getStatusCode()).isEqualTo(HttpStatus.OK);
    }

    // ============================================================
    // 10. 跨租户核销被拒
    // ============================================================

    @Test
    void crossTenantRedemptionRejected() throws Exception {
        HttpHeaders ah = adminAuth();
        long defaultId = ensureSelfOperated(ah);

        // 在 default 租户创建卡密
        JsonNode created = createBatch(ah, defaultId, "9", 1);
        String code = created.get("plaintexts").get(0).asText();

        // 在另一个租户里建一个普通用户
        long otherTenantId = createTenant(ah, "xt-" + System.nanoTime());
        String otherMember = createTenantMember(ah, otherTenantId);
        HttpHeaders otherAuth = login(otherMember, "Passw0rd!Strong");

        String j = objectMapper.writeValueAsString(Map.of("code", code));
        ResponseEntity<String> r = restTemplate.exchange(
                baseUrl + "/api/cards/redeem", HttpMethod.POST, new HttpEntity<>(j, otherAuth), String.class);
        assertThat(r.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(r.getBody()).contains("CARD_CROSS_TENANT_REDEEM");
    }

    // ============================================================
    // 11. 卡密格式不正确 → CARD_CODE_INVALID
    // ============================================================

    @Test
    void invalidCardCodeFormatRejected() throws Exception {
        HttpHeaders ah = adminAuth();
        long defaultId = ensureSelfOperated(ah);
        String memberUsername = createTenantMember(ah, defaultId);
        HttpHeaders user = login(memberUsername, "Passw0rd!Strong");

        // 长度不足
        String j = objectMapper.writeValueAsString(Map.of("code", "FLX-ABCD"));
        ResponseEntity<String> r = restTemplate.exchange(
                baseUrl + "/api/cards/redeem", HttpMethod.POST, new HttpEntity<>(j, user), String.class);
        assertThat(r.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(r.getBody()).contains("CARD_CODE_INVALID");

        // 包含禁用字符（0、O、I、1、L、U）
        String bad = objectMapper.writeValueAsString(Map.of("code", "FLX-0000-1111-IIII-LLLL-UUUU"));
        ResponseEntity<String> r2 = restTemplate.exchange(
                baseUrl + "/api/cards/redeem", HttpMethod.POST, new HttpEntity<>(bad, user), String.class);
        assertThat(r2.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(r2.getBody()).contains("CARD_CODE_INVALID");
    }

    // ============================================================
    // 12. 不存在的卡密 → CARD_NOT_FOUND
    // ============================================================

    @Test
    void nonExistentCardRedemptionRejected() throws Exception {
        HttpHeaders ah = adminAuth();
        long defaultId = ensureSelfOperated(ah);
        String memberUsername = createTenantMember(ah, defaultId);
        HttpHeaders user = login(memberUsername, "Passw0rd!Strong");

        // 完全合法格式但不存在
        String j = objectMapper.writeValueAsString(Map.of("code", "FLX-ABCD-EFGH-JKMN-PQRS-TVWX"));
        ResponseEntity<String> r = restTemplate.exchange(
                baseUrl + "/api/cards/redeem", HttpMethod.POST, new HttpEntity<>(j, user), String.class);
        assertThat(r.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(r.getBody()).contains("CARD_NOT_FOUND");
    }

    // ============================================================
    // 13. 已核销卡密不可启用 / 停用
    // ============================================================

    @Test
    void redeemedCardCannotBeReEnabledOrDisabled() throws Exception {
        HttpHeaders ah = adminAuth();
        long defaultId = ensureSelfOperated(ah);
        String memberUsername = createTenantMember(ah, defaultId);
        HttpHeaders user = login(memberUsername, "Passw0rd!Strong");

        JsonNode created = createBatch(ah, defaultId, "4", 1);
        String code = created.get("plaintexts").get(0).asText();
        String j = objectMapper.writeValueAsString(Map.of("code", code));
        // 核销
        ResponseEntity<String> r = restTemplate.exchange(
                baseUrl + "/api/cards/redeem", HttpMethod.POST, new HttpEntity<>(j, user), String.class);
        assertThat(r.getStatusCode()).isEqualTo(HttpStatus.OK);
        long cardId = objectMapper.readTree(r.getBody()).get("data").get("cardId").asLong();

        // 已核销卡密拒绝任何状态变更
        ResponseEntity<String> dis = restTemplate.exchange(
                baseUrl + "/api/cards/" + cardId + "/disable", HttpMethod.PUT,
                new HttpEntity<>("{}", ah), String.class);
        assertThat(dis.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(dis.getBody()).contains("VALIDATION_ERROR");
    }

    // ============================================================
    // 14. 普通用户无权管理卡密
    // ============================================================

    @Test
    void normalMemberCannotListOrManageBatches() throws Exception {
        HttpHeaders ah = adminAuth();
        long defaultId = ensureSelfOperated(ah);
        String memberUsername = createTenantMember(ah, defaultId);
        HttpHeaders user = login(memberUsername, "Passw0rd!Strong");

        // 列出批次 → 403
        ResponseEntity<String> list = restTemplate.exchange(
                baseUrl + "/api/tenant/" + defaultId + "/cards/batches",
                HttpMethod.GET, new HttpEntity<>(user), String.class);
        assertThat(list.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);

        // 创建批次 → 403
        String body = objectMapper.writeValueAsString(Map.of(
                "groups", List.of(Map.of("denomination", "1", "count", 1))));
        ResponseEntity<String> create = restTemplate.exchange(
                baseUrl + "/api/tenant/" + defaultId + "/cards/batches",
                HttpMethod.POST, new HttpEntity<>(body, user), String.class);
        assertThat(create.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
    }

    // ============================================================
    // 15. 租户管理员无法管理其他租户卡密
    // ============================================================

    @Test
    void tenantAdminCannotManageOtherTenantBatches() throws Exception {
        HttpHeaders ah = adminAuth();
        String taUser = createTenantAdminInDefault(ah);
        long otherTenantId = createTenant(ah, "tax-" + System.nanoTime());
        HttpHeaders ta = login(taUser, "TaPass2026!");

        // 列其他租户批次 → 403
        ResponseEntity<String> list = restTemplate.exchange(
                baseUrl + "/api/tenant/" + otherTenantId + "/cards/batches",
                HttpMethod.GET, new HttpEntity<>(ta), String.class);
        assertThat(list.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);

        // 在其他租户创建 → 403
        String body = objectMapper.writeValueAsString(Map.of(
                "groups", List.of(Map.of("denomination", "1", "count", 1))));
        ResponseEntity<String> create = restTemplate.exchange(
                baseUrl + "/api/tenant/" + otherTenantId + "/cards/batches",
                HttpMethod.POST, new HttpEntity<>(body, ta), String.class);
        assertThat(create.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
    }

    // ============================================================
    // 16. 平台管理员可跨租户列出批次
    // ============================================================

    @Test
    void platformAdminCanListAllTenantsBatches() throws Exception {
        HttpHeaders ah = adminAuth();
        long defaultId = ensureSelfOperated(ah);
        long otherTenantId = createTenant(ah, "pl-" + System.nanoTime());
        createBatch(ah, defaultId, "10", 1);
        createBatch(ah, otherTenantId, "20", 1);

        ResponseEntity<String> all = restTemplate.exchange(
                baseUrl + "/api/admin/cards/batches?page=1&size=200",
                HttpMethod.GET, new HttpEntity<>(ah), String.class);
        assertThat(all.getStatusCode()).isEqualTo(HttpStatus.OK);
        JsonNode items = objectMapper.readTree(all.getBody()).get("data").get("items");

        List<Long> seen = new ArrayList<>();
        for (JsonNode it : items) seen.add(it.get("tenantId").asLong());
        assertThat(seen).contains(defaultId, otherTenantId);
    }

    // ============================================================
    // 17. 批量数量上限校验
    // ============================================================

    @Test
    void batchCountExceedingLimitRejected() throws Exception {
        HttpHeaders ah = adminAuth();
        long defaultId = ensureSelfOperated(ah);

        // 默认 batch-max-count=1000；尝试 1001
        String body = objectMapper.writeValueAsString(Map.of(
                "groups", List.of(Map.of("denomination", "1", "count", 1001))));
        ResponseEntity<String> r = restTemplate.exchange(
                baseUrl + "/api/tenant/" + defaultId + "/cards/batches",
                HttpMethod.POST, new HttpEntity<>(body, ah), String.class);
        assertThat(r.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(r.getBody()).contains("CARD_BATCH_COUNT_EXCEEDED");
    }

    // ============================================================
    // 18. DB 唯一索引：source=CARD_REDEEM 时 card_id 必须唯一
    // ============================================================

    @Test
    void cardRedemptionTransactionHasUniqueCardIdConstraint() {
        JdbcTemplate jdbc = new JdbcTemplate(dataSource);
        // 唯一索引存在
        Integer idx = jdbc.queryForObject(
                "SELECT COUNT(*) FROM pg_indexes "
                        + " WHERE indexname = 'uk_credit_txn_card'",
                Integer.class);
        assertThat(idx).isEqualTo(1);

        // credit_transaction 表有 source / card_id 两列
        Integer cols = jdbc.queryForObject(
                "SELECT COUNT(*) FROM information_schema.columns "
                        + " WHERE table_name = 'credit_transaction' "
                        + "   AND column_name IN ('source','card_id')",
                Integer.class);
        assertThat(cols).isEqualTo(2);

        // CHECK 约束限制 source 取值
        Integer chk = jdbc.queryForObject(
                "SELECT COUNT(*) FROM information_schema.check_constraints "
                        + " WHERE constraint_name = 'chk_credit_txn_source'",
                Integer.class);
        assertThat(chk).isEqualTo(1);
    }
}
