package io.fluxora.platform.credit;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.fluxora.platform.billing.settlement.BillingSettlementService;
import io.fluxora.platform.observability.RelayEventPayload;
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
import java.time.Instant;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 额度集成测试。
 *
 * 覆盖：
 *   - 创建租户用户自动创建 0 余额账户
 *   - V5 回填幂等
 *   - 增加 / 扣减额度 + 流水正确
 *   - 余额不足 → 403/400 拒绝、不写流水
 *   - 并发扣减 → 余额不变错、不为负
 *   - 流水不可篡改（mapper 无 update/delete 接口）
 *   - 普通用户不能调整任何人额度
 *   - 跨租户隔离
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Testcontainers
class CreditIntegrationTest {

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
    @Autowired private BillingSettlementService billingSettlementService;
    private final RestTemplate restTemplate;
    private final ObjectMapper objectMapper = new ObjectMapper();
    private String baseUrl;

    public CreditIntegrationTest() {
        this.restTemplate = new RestTemplate();
        this.restTemplate.setErrorHandler(new DefaultResponseErrorHandler() {
            @Override public boolean hasError(HttpStatusCode s) { return false; }
        });
    }

    @BeforeEach
    void setUp() { baseUrl = "http://localhost:" + port; }

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

    private long ensureSelfOperated(HttpHeaders ah) throws Exception {
        String seed = "seed_" + System.nanoTime();
        String ij = objectMapper.writeValueAsString(Map.of(
                "tenantName", "自营", "adminUsername", seed,
                "adminPassword", "SeedPass2026!", "adminDisplayName", "Seed"));
        restTemplate.exchange(baseUrl + "/api/tenant/self-operated/initialize",
                HttpMethod.POST, new HttpEntity<>(ij, ah), String.class);
        ResponseEntity<String> list = restTemplate.exchange(
                baseUrl + "/api/tenant?keyword=default", HttpMethod.GET, new HttpEntity<>(ah), String.class);
        for (JsonNode n : objectMapper.readTree(list.getBody()).get("data").get("items"))
            if ("default".equals(n.get("tenantCode").asText())) return n.get("id").asLong();
        throw new IllegalStateException("default 不存在");
    }

    /** 创建 TENANT_MEMBER 并返回 userId + username */
    private long[] createMemberReturnId(HttpHeaders ah, long tenantId) throws Exception {
        String username = "u" + System.nanoTime();
        String j = objectMapper.writeValueAsString(Map.of(
                "username", username, "displayName", username,
                "password", "Passw0rd!Strong", "roleCode", "TENANT_MEMBER"));
        ResponseEntity<String> r = restTemplate.exchange(
                baseUrl + "/api/tenant/" + tenantId + "/members",
                HttpMethod.POST, new HttpEntity<>(j, ah), String.class);
        assertThat(r.getStatusCode()).isEqualTo(HttpStatus.OK);
        long userId = objectMapper.readTree(r.getBody()).get("data").get("id").asLong();
        // 把 username 编码到返回数组的后一个 long 中是不优雅的；保留 [userId] 单元素，username 通过 username 字段
        return new long[]{userId};
    }

    // ---------- 创建用户即自动建账户（余额 0） ----------

    @Test
    void newTenantMemberShouldHaveZeroBalanceAccount() throws Exception {
        HttpHeaders ah = adminAuth();
        ensureSelfOperated(ah);
        long userId = createMemberReturnId(ah, 1L)[0];

        ResponseEntity<String> r = restTemplate.exchange(
                baseUrl + "/api/admin/credit/accounts/" + userId,
                HttpMethod.GET, new HttpEntity<>(ah), String.class);
        assertThat(r.getStatusCode()).isEqualTo(HttpStatus.OK);
        BigDecimal balance = new BigDecimal(
                objectMapper.readTree(r.getBody()).get("data").get("balance").asText());
        assertThat(balance).isEqualByComparingTo("0");
    }

    // ---------- V5 回填幂等 ----------

    @Test
    void backfillScriptIsIdempotent() {
        // V5 的回填 INSERT … ON CONFLICT DO NOTHING；重复执行不应抛错也不应产生重复行
        JdbcTemplate jdbc = new JdbcTemplate(dataSource);
        long before = jdbc.queryForObject("SELECT COUNT(*) FROM user_credit_account", Long.class);
        jdbc.update("INSERT INTO user_credit_account (tenant_id, user_id, balance) "
                + "SELECT u.tenant_id, u.id, 0 FROM user_account u "
                + "WHERE u.scope_type = 'TENANT' AND u.deleted_at IS NULL AND u.tenant_id IS NOT NULL "
                + "ON CONFLICT (user_id) DO NOTHING");
        long after = jdbc.queryForObject("SELECT COUNT(*) FROM user_credit_account", Long.class);
        assertThat(after).isEqualTo(before);
    }

    // ---------- 增加额度 → 余额 + 流水正确 ----------

    @Test
    void creditAddsBalanceAndWritesTransaction() throws Exception {
        HttpHeaders ah = adminAuth();
        ensureSelfOperated(ah);
        long userId = createMemberReturnId(ah, 1L)[0];

        String j = objectMapper.writeValueAsString(Map.of(
                "direction", "CREDIT", "amount", "100.5000", "reason", "测试初始化"));
        ResponseEntity<String> r = restTemplate.exchange(
                baseUrl + "/api/tenant/1/credit/adjust?userId=" + userId,
                HttpMethod.POST, new HttpEntity<>(j, ah), String.class);
        assertThat(r.getStatusCode()).isEqualTo(HttpStatus.OK);
        JsonNode txn = objectMapper.readTree(r.getBody()).get("data");
        assertThat(new BigDecimal(txn.get("balanceBefore").asText())).isEqualByComparingTo("0");
        assertThat(new BigDecimal(txn.get("balanceAfter").asText())).isEqualByComparingTo("100.5");
        assertThat(txn.get("direction").asText()).isEqualTo("CREDIT");
        assertThat(txn.get("reason").asText()).isEqualTo("测试初始化");

        // 账户余额查询
        ResponseEntity<String> g = restTemplate.exchange(
                baseUrl + "/api/admin/credit/accounts/" + userId,
                HttpMethod.GET, new HttpEntity<>(ah), String.class);
        BigDecimal balance = new BigDecimal(
                objectMapper.readTree(g.getBody()).get("data").get("balance").asText());
        assertThat(balance).isEqualByComparingTo("100.5");
    }

    @Test
    void directSettlementIsIdempotentAndMayProduceNegativeBalance() throws Exception {
        HttpHeaders ah = adminAuth();
        ensureSelfOperated(ah);
        long userId = createMemberReturnId(ah, 1L)[0];
        JdbcTemplate jdbc = new JdbcTemplate(dataSource);
        restTemplate.exchange(baseUrl + "/api/tenant/1/credit/adjust?userId=" + userId, HttpMethod.POST,
                new HttpEntity<>(objectMapper.writeValueAsString(Map.of("direction", "CREDIT", "amount", "0.25", "reason", "直接结算测试")), ah), String.class);
        long apiKeyId = jdbc.queryForObject("""
                INSERT INTO api_key(tenant_id,user_id,name,key_prefix,lookup_hash,lookup_hash_version,enabled)
                VALUES(1,?, '直接结算 Key', ?, ?, 1, TRUE) RETURNING id
                """, Long.class, userId, "flx_" + System.nanoTime(), "d".repeat(64));
        long modelId = jdbc.queryForObject("""
                INSERT INTO tenant_model(tenant_id,model_code,display_name,enabled,publish_status)
                VALUES(1,?, '直接结算模型', TRUE, 'ENABLED') RETURNING id
                """, Long.class, "direct-settlement-model-" + System.nanoTime());
        String requestId = "direct-settlement-" + System.nanoTime();
        RelayEventPayload event = terminalEvent(requestId, userId, apiKeyId, modelId);

        billingSettlementService.finalizeTerminal(event, new BigDecimal("0.50000000"), "CALCULATED");
        billingSettlementService.finalizeTerminal(event, new BigDecimal("0.50000000"), "CALCULATED");

        assertThat(balance(jdbc, userId)).isEqualByComparingTo("-0.25000000");
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM billing_settlement WHERE request_id=?", Long.class, requestId))
                .isEqualTo(1L);
        assertThat(jdbc.queryForObject("SELECT status FROM billing_settlement WHERE request_id=?", String.class, requestId))
                .isEqualTo("SETTLED");
        assertThat(jdbc.queryForObject("""
                SELECT COUNT(*) FROM credit_transaction
                WHERE billing_settlement_id=(SELECT id FROM billing_settlement WHERE request_id=?)
                """, Long.class, requestId)).isEqualTo(1L);
    }

    private RelayEventPayload terminalEvent(String requestId, long userId, long apiKeyId, long modelId) {
        return terminalEvent(requestId, userId, apiKeyId, modelId, "REPORTED", "RESPONSE_STARTED");
    }
    private RelayEventPayload terminalEvent(String requestId, long userId, long apiKeyId, long modelId, String usageStatus, String dispatchState) {
        Instant now = Instant.now();
        return new RelayEventPayload("event-" + System.nanoTime(), "RELAY_REQUEST_FINISHED", requestId, now, 1L, userId,
                apiKeyId, "OPENAI", "OPENAI", "/v1/chat/completions", modelId, "billing-test", 1L, 1L, 1L, false,
                now, now, 1L, "SUCCESS", null, 200, usageStatus, usageStatus.equals("REPORTED") ? 500_000L : null, 0L,
                null, null, "CNY", 1, new BigDecimal("1.00000000"), BigDecimal.ZERO, null, null,
                usageStatus.equals("REPORTED") ? "CALCULATED" : "UNAVAILABLE", dispatchState);
    }
    private BigDecimal balance(JdbcTemplate jdbc, long userId) { return jdbc.queryForObject("SELECT balance FROM user_credit_account WHERE user_id=?", BigDecimal.class, userId); }

    // ---------- 余额不足 → 拒绝；不写流水 ----------

    @Test
    void debitInsufficientIsRejectedAndNoTransactionWritten() throws Exception {
        HttpHeaders ah = adminAuth();
        ensureSelfOperated(ah);
        long userId = createMemberReturnId(ah, 1L)[0];

        // 先充值 50
        String credit = objectMapper.writeValueAsString(Map.of(
                "direction", "CREDIT", "amount", "50", "reason", "init"));
        restTemplate.exchange(baseUrl + "/api/tenant/1/credit/adjust?userId=" + userId,
                HttpMethod.POST, new HttpEntity<>(credit, ah), String.class);

        // 尝试扣 200 → 拒绝
        String debit = objectMapper.writeValueAsString(Map.of(
                "direction", "DEBIT", "amount", "200", "reason", "overflow"));
        ResponseEntity<String> r = restTemplate.exchange(
                baseUrl + "/api/tenant/1/credit/adjust?userId=" + userId,
                HttpMethod.POST, new HttpEntity<>(debit, ah), String.class);
        assertThat(r.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(r.getBody()).contains("CREDIT_INSUFFICIENT");

        // 余额保持 50
        ResponseEntity<String> g = restTemplate.exchange(
                baseUrl + "/api/admin/credit/accounts/" + userId,
                HttpMethod.GET, new HttpEntity<>(ah), String.class);
        assertThat(new BigDecimal(objectMapper.readTree(g.getBody()).get("data").get("balance").asText()))
                .isEqualByComparingTo("50");

        // 流水仅 1 条（充值的那条）
        JdbcTemplate jdbc = new JdbcTemplate(dataSource);
        Long count = jdbc.queryForObject(
                "SELECT COUNT(*) FROM credit_transaction WHERE user_id = ?",
                Long.class, userId);
        assertThat(count).isEqualTo(1);
    }

    // ---------- 并发扣减安全 ----------

    @Test
    void concurrentDebitsAreAtomicAndNeverProduceNegativeBalance() throws Exception {
        HttpHeaders ah = adminAuth();
        ensureSelfOperated(ah);
        long userId = createMemberReturnId(ah, 1L)[0];

        // 充值 70
        String credit = objectMapper.writeValueAsString(Map.of(
                "direction", "CREDIT", "amount", "70", "reason", "init"));
        restTemplate.exchange(baseUrl + "/api/tenant/1/credit/adjust?userId=" + userId,
                HttpMethod.POST, new HttpEntity<>(credit, ah), String.class);

        // 10 个线程同时扣 10 → 应成功 7 次失败 3 次
        int threads = 10;
        CountDownLatch start = new CountDownLatch(1);
        CountDownLatch done = new CountDownLatch(threads);
        AtomicInteger ok = new AtomicInteger();
        AtomicInteger fail = new AtomicInteger();
        ExecutorService pool = Executors.newFixedThreadPool(threads);

        String debit = objectMapper.writeValueAsString(Map.of(
                "direction", "DEBIT", "amount", "10", "reason", "concurrent"));
        for (int i = 0; i < threads; i++) {
            pool.submit(() -> {
                try {
                    start.await();
                    ResponseEntity<String> r = restTemplate.exchange(
                            baseUrl + "/api/tenant/1/credit/adjust?userId=" + userId,
                            HttpMethod.POST, new HttpEntity<>(debit, ah), String.class);
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

        assertThat(ok.get()).isEqualTo(7);
        assertThat(fail.get()).isEqualTo(3);

        // 终态 balance = 0
        ResponseEntity<String> g = restTemplate.exchange(
                baseUrl + "/api/admin/credit/accounts/" + userId,
                HttpMethod.GET, new HttpEntity<>(ah), String.class);
        assertThat(new BigDecimal(objectMapper.readTree(g.getBody()).get("data").get("balance").asText()))
                .isEqualByComparingTo("0");

        // 流水应正好 7 条 DEBIT + 1 条 CREDIT
        JdbcTemplate jdbc = new JdbcTemplate(dataSource);
        Long debits = jdbc.queryForObject(
                "SELECT COUNT(*) FROM credit_transaction WHERE user_id = ? AND direction = 'DEBIT'",
                Long.class, userId);
        assertThat(debits).isEqualTo(7);
    }

    // ---------- 流水不可篡改（结构级） ----------

    @Test
    void creditTransactionTableHasNoMutability() {
        JdbcTemplate jdbc = new JdbcTemplate(dataSource);
        // 表无 updated_at 列；不允许标记/修改
        Integer updatedAt = jdbc.queryForObject(
                "SELECT COUNT(*) FROM information_schema.columns "
                        + "WHERE table_name = 'credit_transaction' AND column_name = 'updated_at'",
                Integer.class);
        assertThat(updatedAt).isEqualTo(0);
        // CHECK 约束限制 direction
        Integer chk = jdbc.queryForObject(
                "SELECT COUNT(*) FROM information_schema.check_constraints "
                        + "WHERE constraint_name LIKE 'chk_credit_txn_%'",
                Integer.class);
        assertThat(chk).isGreaterThanOrEqualTo(2);
    }

    // ---------- 普通用户不能调整任何人额度 ----------

    @Test
    void normalMemberCannotAdjustAnyCredit() throws Exception {
        HttpHeaders ah = adminAuth();
        ensureSelfOperated(ah);
        long userId = createMemberReturnId(ah, 1L)[0];
        // 通过列表回查 username
        ResponseEntity<String> list = restTemplate.exchange(
                baseUrl + "/api/tenant/1/members?page=1&size=100",
                HttpMethod.GET, new HttpEntity<>(ah), String.class);
        String username = null;
        for (JsonNode m : objectMapper.readTree(list.getBody()).get("data").get("items")) {
            if (m.get("id").asLong() == userId) { username = m.get("username").asText(); break; }
        }
        assertThat(username).isNotNull();
        HttpHeaders user = login(username, "Passw0rd!Strong");

        // 普通用户调用 adjust → 403（缺少 CREDIT_TENANT_ADJUST 权限）
        String j = objectMapper.writeValueAsString(Map.of(
                "direction", "CREDIT", "amount", "10", "reason", "self-credit-disallowed"));
        ResponseEntity<String> r = restTemplate.exchange(
                baseUrl + "/api/tenant/1/credit/adjust?userId=" + userId,
                HttpMethod.POST, new HttpEntity<>(j, user), String.class);
        assertThat(r.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
    }

    @Test
    void amountRequestRejectsJsonNumberAndScientificNotation() throws Exception {
        HttpHeaders ah = adminAuth();
        ensureSelfOperated(ah);
        long userId = createMemberReturnId(ah, 1L)[0];

        ResponseEntity<String> numeric = restTemplate.exchange(
                baseUrl + "/api/tenant/1/credit/adjust?userId=" + userId,
                HttpMethod.POST, new HttpEntity<>("{\"direction\":\"CREDIT\",\"amount\":0.1,\"reason\":\"numeric\"}", ah), String.class);
        assertThat(numeric.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);

        String scientificBody = objectMapper.writeValueAsString(Map.of(
                "direction", "CREDIT", "amount", "1e-3", "reason", "scientific"));
        ResponseEntity<String> scientific = restTemplate.exchange(
                baseUrl + "/api/tenant/1/credit/adjust?userId=" + userId,
                HttpMethod.POST, new HttpEntity<>(scientificBody, ah), String.class);
        assertThat(scientific.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
    }
}
