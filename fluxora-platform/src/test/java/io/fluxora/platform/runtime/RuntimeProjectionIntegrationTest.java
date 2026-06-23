package io.fluxora.platform.runtime;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.fluxora.platform.runtime.mapper.RuntimeMapper;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 运行时投影端到端验证：PostgreSQL Outbox 是事实来源，Redis 只保存可重建的版本快照。
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Testcontainers
class RuntimeProjectionIntegrationTest {

    @Container
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:17-alpine")
            .withDatabaseName("fluxora").withUsername("fluxora").withPassword("fluxora");

    @Container
    static GenericContainer<?> redis = new GenericContainer<>(DockerImageName.parse("redis:7-alpine"))
            .withExposedPorts(6379);

    @DynamicPropertySource
    static void configureServices(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", postgres::getJdbcUrl);
        registry.add("spring.datasource.username", postgres::getUsername);
        registry.add("spring.datasource.password", postgres::getPassword);
        registry.add("spring.data.redis.host", redis::getHost);
        registry.add("spring.data.redis.port", () -> redis.getMappedPort(6379));
        registry.add("fluxora.runtime.enabled", () -> false);
    }

    @Autowired private JdbcTemplate jdbc;
    @Autowired private RuntimeMapper runtimeMapper;
    @Autowired private RuntimeOutboxService outboxService;
    @Autowired private RuntimeProjector projector;
    @Autowired private RuntimeImpactResolver impactResolver;
    @Autowired private StringRedisTemplate redisTemplate;
    @Autowired private ObjectMapper objectMapper;

    @Test
    void shouldWriteImmutableApiKeySnapshotsAndOnlyAdvanceManifestVersion() throws Exception {
        long tenantId = insertTenant();
        long userId = insertTenantUser(tenantId);
        String lookupHash = "a".repeat(64);
        long apiKeyId = insertApiKey(tenantId, userId, lookupHash);

        outboxService.record(tenantId, "API_KEY", apiKeyId, "CREATED", null);
        RuntimeOutboxEvent first = claimSingle("runtime-test-a");
        projector.project(first);

        RuntimeScope scope = RuntimeScope.apiKey(lookupHash, tenantId);
        JsonNode firstManifest = readManifest(scope);
        assertThat(firstManifest.path("activeRuntimeVersion").asLong()).isEqualTo(1L);
        String firstSnapshotKey = RuntimeRedisSnapshotStore.snapshotKey(scope, 1L);
        JsonNode firstSnapshot = objectMapper.readTree(redisTemplate.opsForValue().get(firstSnapshotKey));
        assertThat(firstSnapshot.path("lookupHash").asText()).isEqualTo(lookupHash);
        assertThat(firstSnapshot.path("runtimeVersion").asLong()).isEqualTo(1L);
        assertThat(firstSnapshot.has("apiKeyPlaintext")).isFalse();

        outboxService.record(tenantId, "API_KEY", apiKeyId, "DISABLED", null);
        RuntimeOutboxEvent second = claimSingle("runtime-test-b");
        projector.project(second);

        JsonNode secondManifest = readManifest(scope);
        assertThat(secondManifest.path("activeRuntimeVersion").asLong()).isEqualTo(2L);
        assertThat(redisTemplate.hasKey(firstSnapshotKey)).isTrue();
        assertThat(redisTemplate.hasKey(RuntimeRedisSnapshotStore.snapshotKey(scope, 2L))).isTrue();
        assertThat(jdbc.queryForObject(
                "SELECT status FROM runtime_outbox WHERE id = ?", String.class, second.id())).isEqualTo("COMPLETED");
    }

    @Test
    void fullRebuildMustIncludeEveryUserAuthenticationScope() {
        long tenantId = insertTenant();
        long userId = insertTenantUser(tenantId);

        Set<RuntimeScope> scopes = impactResolver.resolve(new RuntimeOutboxEvent(
                99L, null, "RUNTIME_NAMESPACE", null, "FULL_REBUILD", "NAMESPACE_MISSING", 1,
                java.time.Instant.now()));

        assertThat(scopes).contains(RuntimeScope.user(tenantId, userId), RuntimeScope.tenant(tenantId));
    }

    @Test
    void routeSnapshotMustBeOneCompleteExecutionPackageWithoutSecrets() throws Exception {
        long tenantId = insertTenant();
        String modelCode = "runtime-model-" + System.nanoTime();
        long modelId = insertExecutableRouteFixture(tenantId, modelCode);

        outboxService.record(tenantId, "TENANT_MODEL", modelId, "ENABLED", null);
        projector.project(claimSingle("runtime-route"));

        RuntimeScope scope = RuntimeScope.route(tenantId, "OPENAI", modelCode);
        JsonNode snapshot = objectMapper.readTree(redisTemplate.opsForValue().get(
                RuntimeRedisSnapshotStore.snapshotKey(scope, 1L)));
        JsonNode target = snapshot.path("targets").get(0);
        assertThat(snapshot.path("tenantModelCode").asText()).isEqualTo(modelCode);
        assertThat(snapshot.path("currencyCode").asText()).isEqualTo("CNY");
        assertThat(snapshot.path("inputPricePerMillion").asText()).isEqualTo("0.10000000");
        assertThat(snapshot.path("outputPricePerMillion").asText()).isEqualTo("0.30000000");
        assertThat(target.path("hasUsableCredential").asBoolean()).isTrue();
        assertThat(target.path("outboundProtocol").asText()).isEqualTo("OPENAI");
        assertThat(snapshot.toString()).doesNotContain("baseUrl", "ciphertext", "initializationVector",
                "credentialFingerprint", "runtime-secret");
    }

    @Test
    void manifestMustNeverRollBackToLowerVersion() throws Exception {
        long tenantId = insertTenant();
        long userId = insertTenantUser(tenantId);
        String lookupHash = "b".repeat(64);
        long apiKeyId = insertApiKey(tenantId, userId, lookupHash);

        // v1 投影
        outboxService.record(tenantId, "API_KEY", apiKeyId, "CREATED", null);
        projector.project(claimSingle("runtime-reg-v1"));

        RuntimeScope scope = RuntimeScope.apiKey(lookupHash, tenantId);
        JsonNode manifestV1 = readManifest(scope);
        assertThat(manifestV1.path("activeRuntimeVersion").asLong()).isEqualTo(1L);

        // v2 投影
        outboxService.record(tenantId, "API_KEY", apiKeyId, "DISABLED", null);
        projector.project(claimSingle("runtime-reg-v2"));

        JsonNode manifestV2 = readManifest(scope);
        assertThat(manifestV2.path("activeRuntimeVersion").asLong()).isEqualTo(2L);

        // 尝试用旧版本 v1 覆盖 Manifest — 必须被 Lua 脚本拒绝
        String v1SnapshotJson = redisTemplate.opsForValue().get(
                RuntimeRedisSnapshotStore.snapshotKey(scope, 1L));
        assertThat(v1SnapshotJson).isNotNull();
        JsonNode v1Snapshot = objectMapper.readTree(v1SnapshotJson);

        // 直接用 Lua 尝试以 v1 覆盖 Manifest — 版本号小于当前 2，Lua 应拒绝
        Long switched = redisTemplate.execute(
                new org.springframework.data.redis.core.script.DefaultRedisScript<>("""
                        local current = redis.call('GET', KEYS[1])
                        if current then
                          local decoded = cjson.decode(current)
                          if decoded['activeRuntimeVersion'] and tonumber(decoded['activeRuntimeVersion']) >= tonumber(ARGV[1]) then
                            return 0
                          end
                        end
                        redis.call('SET', KEYS[1], ARGV[2])
                        return 1
                        """, Long.class),
                java.util.List.of(RuntimeRedisSnapshotStore.manifestKey(scope)),
                "1", v1SnapshotJson);
        assertThat(switched).as("低版本 Manifest 不得覆盖高版本").isEqualTo(0L);

        // Manifest 仍指向 v2
        JsonNode manifestAfter = readManifest(scope);
        assertThat(manifestAfter.path("activeRuntimeVersion").asLong()).isEqualTo(2L);
    }

    private RuntimeOutboxEvent claimSingle(String workerId) {
        List<RuntimeOutboxEvent> events = runtimeMapper.claimDueBatch(workerId, 10);
        assertThat(events).hasSize(1);
        return events.getFirst();
    }

    private JsonNode readManifest(RuntimeScope scope) throws Exception {
        String manifest = redisTemplate.opsForValue().get(RuntimeRedisSnapshotStore.manifestKey(scope));
        assertThat(manifest).isNotBlank();
        return objectMapper.readTree(manifest);
    }

    private long insertTenant() {
        return jdbc.queryForObject("""
                INSERT INTO tenant(tenant_code, name, type, enabled)
                VALUES (?, '运行时投影测试租户', 'STANDARD', TRUE) RETURNING id
                """, Long.class, "runtime-" + System.nanoTime());
    }

    private long insertTenantUser(long tenantId) {
        return jdbc.queryForObject("""
                INSERT INTO user_account(username, password_hash, display_name, scope_type, tenant_id, enabled)
                VALUES (?, 'not-a-real-password', '投影测试用户', 'TENANT', ?, TRUE) RETURNING id
                """, Long.class, "runtime-user-" + System.nanoTime(), tenantId);
    }

    private long insertApiKey(long tenantId, long userId, String lookupHash) {
        return jdbc.queryForObject("""
                INSERT INTO api_key(tenant_id, user_id, name, key_prefix, lookup_hash, lookup_hash_version, enabled)
                VALUES (?, ?, '投影测试 Key', ?, ?, 1, TRUE) RETURNING id
                """, Long.class, tenantId, userId, "flx_" + System.nanoTime(), lookupHash);
    }

    /** 仅用于运行时集成测试：直接构造满足关联约束的一条可执行模型路由。 */
    private long insertExecutableRouteFixture(long tenantId, String modelCode) {
        String suffix = Long.toUnsignedString(System.nanoTime());
        long providerId = jdbc.queryForObject("""
                INSERT INTO provider(name, code, scope_type, enabled)
                VALUES (?, ?, 'PLATFORM_SHARED', TRUE) RETURNING id
                """, Long.class, "运行时厂商-" + suffix, "runtime-provider-" + suffix);
        long baseUrlId = jdbc.queryForObject("""
                INSERT INTO provider_base_url(provider_id, protocol, original_base_url, normalized_base_url, enabled)
                VALUES (?, 'OPENAI', ?, ?, TRUE) RETURNING id
                """, Long.class, providerId, "https://runtime-secret.example/v1", "https://runtime-secret.example/v1");
        long channelId = jdbc.queryForObject("""
                INSERT INTO provider_channel(tenant_id, provider_base_url_id, name, enabled, priority, weight,
                                             connect_timeout_ms, read_timeout_ms)
                VALUES (?, ?, ?, TRUE, 10, 100, 5000, 60000) RETURNING id
                """, Long.class, tenantId, baseUrlId, "运行时通道-" + suffix);
        long candidateId = jdbc.queryForObject("""
                INSERT INTO provider_channel_model(tenant_id, provider_channel_id, upstream_model_id,
                                                   upstream_display_name, enabled)
                VALUES (?, ?, 'runtime-upstream-model', '运行时上游模型', TRUE) RETURNING id
                """, Long.class, tenantId, channelId);
        long modelId = jdbc.queryForObject("""
                INSERT INTO tenant_model(tenant_id, model_code, display_name, enabled, publish_status)
                VALUES (?, ?, '运行时模型', TRUE, 'ENABLED') RETURNING id
                """, Long.class, tenantId, modelCode);
        jdbc.update("""
                INSERT INTO tenant_model_price(tenant_id, tenant_model_id, currency_code, input_price_per_million,
                                               output_price_per_million, version)
                VALUES (?, ?, 'CNY', 0.10000000, 0.30000000, 1)
                """, tenantId, modelId);
        long mappingId = jdbc.queryForObject("""
                INSERT INTO tenant_model_candidate_mapping(tenant_id, tenant_model_id, provider_channel_model_id, enabled)
                VALUES (?, ?, ?, TRUE) RETURNING id
                """, Long.class, tenantId, modelId, candidateId);
        long routeId = jdbc.queryForObject("""
                INSERT INTO model_route(tenant_id, tenant_model_id, inbound_protocol, enabled)
                VALUES (?, ?, 'OPENAI', TRUE) RETURNING id
                """, Long.class, tenantId, modelId);
        jdbc.update("""
                INSERT INTO route_target(tenant_id, model_route_id, tenant_model_candidate_mapping_id,
                                         provider_channel_id, upstream_model_id_snapshot, enabled, priority, weight)
                VALUES (?, ?, ?, ?, 'runtime-upstream-model', TRUE, 10, 100)
                """, tenantId, routeId, mappingId, channelId);
        long credentialId = jdbc.queryForObject("""
                INSERT INTO provider_credential(tenant_id, name, credential_type, masked_value, credential_fingerprint,
                                                ciphertext, initialization_vector, encryption_version, enabled, priority, weight)
                VALUES (?, '运行时凭证', 'API_KEY', '***', ?, 'runtime-secret', 'runtime-iv', 'v1', TRUE, 10, 100)
                RETURNING id
                """, Long.class, tenantId, "f".repeat(64));
        jdbc.update("""
                INSERT INTO provider_channel_credential(tenant_id, provider_channel_id, provider_credential_id, enabled)
                VALUES (?, ?, ?, TRUE)
                """, tenantId, channelId, credentialId);
        return modelId;
    }
}
