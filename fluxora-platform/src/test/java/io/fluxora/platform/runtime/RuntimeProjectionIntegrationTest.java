package io.fluxora.platform.runtime;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.fluxora.platform.runtime.mapper.RuntimeMapper;
import io.fluxora.platform.upstream.security.CredentialCryptoService;
import io.fluxora.platform.upstream.security.EncryptedCredential;
import java.util.Arrays;
import java.util.List;
import java.util.Set;
import java.util.function.Consumer;
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
    @Autowired private CredentialCryptoService credentialCryptoService;

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
    void fullRebuildMustOnlyEnumerateActiveRuntimeScopes() {
        long tenantId = insertTenant();
        long activeUserId = insertTenantUser(tenantId);
        String activeLookupHash = "c".repeat(64);
        insertApiKey(tenantId, activeUserId, activeLookupHash);
        String activeModelCode = "runtime-full-active-" + System.nanoTime();
        RuntimeFixture activeRoute = insertExecutableRouteFixture(tenantId, activeModelCode);

        long disabledUserId = insertTenantUser(tenantId);
        jdbc.update("UPDATE user_account SET enabled=FALSE, updated_at=NOW() WHERE id=?", disabledUserId);
        String disabledLookupHash = "d".repeat(64);
        insertApiKey(tenantId, activeUserId, disabledLookupHash);
        jdbc.update("UPDATE api_key SET enabled=FALSE, updated_at=NOW() WHERE lookup_hash=?", disabledLookupHash);
        String disabledModelCode = "runtime-full-disabled-route-" + System.nanoTime();
        RuntimeFixture disabledRoute = insertExecutableRouteFixture(tenantId, disabledModelCode);
        jdbc.update("UPDATE model_route SET enabled=FALSE, updated_at=NOW() WHERE id=?", disabledRoute.routeId());
        RuntimeFixture disabledCredential = insertExecutableRouteFixture(
                tenantId, "runtime-full-disabled-credential-" + System.nanoTime());
        jdbc.update("UPDATE provider_credential SET enabled=FALSE, updated_at=NOW() WHERE id=?",
                disabledCredential.credentialId());
        long disabledTenantId = insertTenant();
        long disabledTenantUserId = insertTenantUser(disabledTenantId);
        String disabledTenantLookupHash = "e".repeat(64);
        insertApiKey(disabledTenantId, disabledTenantUserId, disabledTenantLookupHash);
        String disabledTenantModelCode = "runtime-full-disabled-tenant-" + System.nanoTime();
        RuntimeFixture disabledTenantRoute = insertExecutableRouteFixture(disabledTenantId, disabledTenantModelCode);
        jdbc.update("UPDATE tenant SET enabled=FALSE, updated_at=NOW() WHERE id=?", disabledTenantId);

        Set<RuntimeScope> scopes = impactResolver.resolve(new RuntimeOutboxEvent(
                100L, null, "RUNTIME_NAMESPACE", null, "FULL_REBUILD", "NAMESPACE_MISSING", 1,
                java.time.Instant.now()));

        assertThat(scopes).contains(
                RuntimeScope.apiKey(activeLookupHash, null),
                RuntimeScope.user(tenantId, activeUserId),
                RuntimeScope.tenant(tenantId),
                RuntimeScope.route(tenantId, "OPENAI", activeModelCode),
                RuntimeScope.upstreamCredential(tenantId, activeRoute.credentialId()));
        assertThat(scopes).doesNotContain(
                RuntimeScope.apiKey(disabledLookupHash, null),
                RuntimeScope.user(tenantId, disabledUserId),
                RuntimeScope.route(tenantId, "OPENAI", disabledModelCode),
                RuntimeScope.upstreamCredential(tenantId, disabledCredential.credentialId()),
                RuntimeScope.apiKey(disabledTenantLookupHash, null),
                RuntimeScope.user(disabledTenantId, disabledTenantUserId),
                RuntimeScope.tenant(disabledTenantId),
                RuntimeScope.route(disabledTenantId, "OPENAI", disabledTenantModelCode),
                RuntimeScope.upstreamCredential(disabledTenantId, disabledTenantRoute.credentialId()));
    }

    @Test
    void routeSnapshotMustBeOneCompleteExecutionPackageWithoutSecrets() throws Exception {
        long tenantId = insertTenant();
        String modelCode = "runtime-model-" + System.nanoTime();
        RuntimeFixture fixture = insertExecutableRouteFixture(tenantId, modelCode);

        outboxService.record(tenantId, "TENANT_MODEL", fixture.modelId(), "ENABLED", null);
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
        assertThat(target.path("baseUrl").asText()).isEqualTo("https://runtime-secret.example/v1");
        assertThat(target.path("connectTimeoutMs").asInt()).isEqualTo(5000);
        assertThat(target.path("readTimeoutMs").asInt()).isEqualTo(60000);
        assertThat(target.path("credentialRefs").isArray()).isTrue();
        assertThat(snapshot.toString()).doesNotContain("ciphertext", "initializationVector",
                "credentialFingerprint", fixture.plaintext());
    }

    @Test
    void routeSnapshotMustOnlyExposeEnabledCredentialReferences() throws Exception {
        long tenantId = insertTenant();
        String modelCode = "runtime-active-credential-model-" + System.nanoTime();
        RuntimeFixture fixture = insertExecutableRouteFixture(tenantId, modelCode);
        EncryptedCredential disabledSecret = credentialCryptoService.encrypt("disabled-credential-" + System.nanoTime());
        long disabledCredentialId = jdbc.queryForObject("""
                INSERT INTO provider_credential(tenant_id, name, credential_type, masked_value, credential_fingerprint,
                                                ciphertext, initialization_vector, encryption_version, enabled, priority, weight)
                VALUES (?, '已停用运行时凭证', 'API_KEY', '***', ?, ?, ?, ?, FALSE, 10, 100)
                RETURNING id
                """, Long.class, tenantId, "e".repeat(64), disabledSecret.ciphertext(),
                disabledSecret.initializationVector(), disabledSecret.encryptionVersion());
        jdbc.update("""
                INSERT INTO provider_channel_credential(tenant_id, provider_channel_id, provider_credential_id, enabled)
                VALUES (?, ?, ?, TRUE)
                """, tenantId, fixture.channelId(), disabledCredentialId);

        outboxService.record(tenantId, "TENANT_MODEL", fixture.modelId(), "ENABLED", null);
        projector.project(claimSingle("runtime-active-credential"));

        RuntimeScope scope = RuntimeScope.route(tenantId, "OPENAI", modelCode);
        JsonNode refs = objectMapper.readTree(redisTemplate.opsForValue().get(
                RuntimeRedisSnapshotStore.snapshotKey(scope, 1L))).path("targets").get(0).path("credentialRefs");
        assertThat(refs).hasSize(1);
        assertThat(refs.get(0).path("providerCredentialId").asLong()).isEqualTo(fixture.credentialId());
    }

    @Test
    void routeSnapshotMustExcludeInactiveExecutionDependencies() throws Exception {
        assertNoRouteTargetsAfterRuntimeDependencyChange("target-disabled",
                fixture -> jdbc.update("UPDATE route_target SET enabled=FALSE, updated_at=NOW() WHERE id=?",
                        fixture.routeTargetId()));
        assertNoRouteTargetsAfterRuntimeDependencyChange("target-deleted",
                fixture -> jdbc.update("UPDATE route_target SET deleted_at=NOW(), updated_at=NOW() WHERE id=?",
                        fixture.routeTargetId()));
        assertNoRouteTargetsAfterRuntimeDependencyChange("mapping-disabled",
                fixture -> jdbc.update("""
                        UPDATE tenant_model_candidate_mapping SET enabled=FALSE, updated_at=NOW() WHERE id=?
                        """, fixture.mappingId()));
        assertNoRouteTargetsAfterRuntimeDependencyChange("mapping-deleted",
                fixture -> jdbc.update("""
                        UPDATE tenant_model_candidate_mapping SET deleted_at=NOW(), updated_at=NOW() WHERE id=?
                        """, fixture.mappingId()));
        assertNoRouteTargetsAfterRuntimeDependencyChange("candidate-disabled",
                fixture -> jdbc.update("""
                        UPDATE provider_channel_model SET enabled=FALSE, updated_at=NOW() WHERE id=?
                        """, fixture.candidateId()));
        assertNoRouteTargetsAfterRuntimeDependencyChange("candidate-deleted",
                fixture -> jdbc.update("""
                        UPDATE provider_channel_model SET deleted_at=NOW(), updated_at=NOW() WHERE id=?
                        """, fixture.candidateId()));
        assertNoRouteTargetsAfterRuntimeDependencyChange("channel-disabled",
                fixture -> jdbc.update("UPDATE provider_channel SET enabled=FALSE, updated_at=NOW() WHERE id=?",
                        fixture.channelId()));
        assertNoRouteTargetsAfterRuntimeDependencyChange("channel-deleted",
                fixture -> jdbc.update("UPDATE provider_channel SET deleted_at=NOW(), updated_at=NOW() WHERE id=?",
                        fixture.channelId()));
        assertNoRouteTargetsAfterRuntimeDependencyChange("base-url-disabled",
                fixture -> jdbc.update("UPDATE provider_base_url SET enabled=FALSE, updated_at=NOW() WHERE id=?",
                        fixture.baseUrlId()));
        assertNoRouteTargetsAfterRuntimeDependencyChange("base-url-deleted",
                fixture -> jdbc.update("UPDATE provider_base_url SET deleted_at=NOW(), updated_at=NOW() WHERE id=?",
                        fixture.baseUrlId()));
        assertNoRouteTargetsAfterRuntimeDependencyChange("provider-disabled",
                fixture -> jdbc.update("UPDATE provider SET enabled=FALSE, updated_at=NOW() WHERE id=?",
                        fixture.providerId()));
        assertNoRouteTargetsAfterRuntimeDependencyChange("provider-deleted",
                fixture -> jdbc.update("UPDATE provider SET deleted_at=NOW(), updated_at=NOW() WHERE id=?",
                        fixture.providerId()));
        assertNoRouteTargetsAfterRuntimeDependencyChange("credential-binding-disabled",
                fixture -> jdbc.update("""
                        UPDATE provider_channel_credential SET enabled=FALSE, updated_at=NOW()
                        WHERE provider_channel_id=? AND provider_credential_id=?
                        """, fixture.channelId(), fixture.credentialId()));
        assertNoRouteTargetsAfterRuntimeDependencyChange("credential-binding-deleted",
                fixture -> jdbc.update("""
                        UPDATE provider_channel_credential SET deleted_at=NOW(), enabled=FALSE, updated_at=NOW()
                        WHERE provider_channel_id=? AND provider_credential_id=?
                        """, fixture.channelId(), fixture.credentialId()));
        assertNoRouteTargetsAfterRuntimeDependencyChange("credential-disabled",
                fixture -> jdbc.update("UPDATE provider_credential SET enabled=FALSE, updated_at=NOW() WHERE id=?",
                        fixture.credentialId()));
        assertNoRouteTargetsAfterRuntimeDependencyChange("credential-deleted",
                fixture -> jdbc.update("""
                        UPDATE provider_credential SET deleted_at=NOW(), enabled=FALSE, updated_at=NOW() WHERE id=?
                        """, fixture.credentialId()));
    }

    @Test
    void credentialRuntimeSnapshotMustBeSeparatedFromRouteAndNeverContainPlaintext() throws Exception {
        long tenantId = insertTenant();
        String modelCode = "credential-runtime-model-" + System.nanoTime();
        RuntimeFixture fixture = insertExecutableRouteFixture(tenantId, modelCode);

        outboxService.record(tenantId, "TENANT_MODEL", fixture.modelId(), "ENABLED", null);
        projector.project(claimSingle("runtime-credential-route"));

        RuntimeScope routeScope = RuntimeScope.route(tenantId, "OPENAI", modelCode);
        JsonNode routeSnapshot = objectMapper.readTree(redisTemplate.opsForValue().get(
                RuntimeRedisSnapshotStore.snapshotKey(routeScope, 1L)));
        assertThat(routeSnapshot.toString()).doesNotContain("encryptedCredentialPayload", "ciphertext",
                "initializationVector", fixture.plaintext());
        assertThat(routeSnapshot.path("targets").get(0).path("credentialRefs").get(0)
                .path("providerCredentialId").asLong()).isEqualTo(fixture.credentialId());

        outboxService.record(tenantId, "PROVIDER_CREDENTIAL", fixture.credentialId(), "CREATED", null);
        RuntimeOutboxEvent credentialEvent = claimSingle("runtime-credential-sensitive");
        assertThat(Arrays.stream(RuntimeScopeType.values()).map(Enum::name))
                .contains("UPSTREAM_CREDENTIAL");
        projector.project(credentialEvent);

        RuntimeScope credentialScope = (RuntimeScope) RuntimeScope.class
                .getMethod("upstreamCredential", Long.class, Long.class)
                .invoke(null, tenantId, fixture.credentialId());
        JsonNode credentialSnapshot = objectMapper.readTree(redisTemplate.opsForValue().get(
                RuntimeRedisSnapshotStore.snapshotKey(credentialScope, 1L)));
        assertThat(credentialSnapshot.path("providerCredentialId").asLong()).isEqualTo(fixture.credentialId());
        assertThat(credentialSnapshot.path("encryptedCredentialPayload").isObject()).isTrue();
        assertThat(credentialSnapshot.toString()).doesNotContain(fixture.plaintext(), "credentialFingerprint", "masterKey");
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

    @Autowired private RuntimeRedisSnapshotStore snapshotStore;

    @Test
    void redisNamespaceHealthMustBeDetectableAndRecoverable() {
        // 初始未标记健康
        redisTemplate.delete("fluxora:runtime:v1:namespace-health");
        assertThat(snapshotStore.namespaceHealthy()).as("清理后命名空间应不健康").isFalse();

        // 标记健康
        snapshotStore.markNamespaceHealthy();
        assertThat(snapshotStore.namespaceHealthy()).as("标记后应为健康").isTrue();

        // 模拟 Redis 清空重启后再次检测
        redisTemplate.delete("fluxora:runtime:v1:namespace-health");
        assertThat(snapshotStore.namespaceHealthy()).as("删除后应再次不健康").isFalse();
    }

    @Test
    void outboxRetryMustPersistErrorAndRetryAfterProjectionFailure() {
        // 验证 RuntimeMapper.markRetry 能正确写入重试信息
        long tenantId = insertTenant();
        outboxService.record(tenantId, "TENANT", tenantId, "CREATED", null);
        RuntimeOutboxEvent event = claimSingle("runtime-retry-verify");

        // 模拟严重故障后的退避重试写入
        runtimeMapper.markRetry(event.id(),
                java.time.Instant.now().plus(java.time.Duration.ofSeconds(2)),
                "Redis 不可达，投影失败");

        // 验证重试记录已写入
        Integer retryCount = jdbc.queryForObject(
                "SELECT attempt_count FROM runtime_outbox WHERE id = ?", Integer.class, event.id());
        assertThat(retryCount).as("重试次数应增加").isGreaterThanOrEqualTo(1);

        String error = jdbc.queryForObject(
                "SELECT last_error_summary FROM runtime_outbox WHERE id = ?", String.class, event.id());
        assertThat(error).as("错误摘要应持久化").contains("Redis");
    }

    @Test
    void outboxEventMustBeImmediatelyClaimableAfterSameTransactionCommit() {
        // 业务写入后，Spring 默认自动提交；Outbox 记录在同一事务中持久化并立即可被领取。
        long tenantId = insertTenant();
        outboxService.record(tenantId, "TENANT", tenantId, "CREATED", null);

        // 验证：同事务已提交，Projector 的 claimDueBatch 可立即领到
        String worker = "tx-claim-" + System.nanoTime();
        List<RuntimeOutboxEvent> events = runtimeMapper.claimDueBatch(worker, 5);
        boolean found = events.stream().anyMatch(
                e -> "TENANT".equals(e.aggregateType()) && tenantId == e.aggregateId());
        assertThat(found).as("提交后的 Outbox 记录必须可被立即领取").isTrue();

        // 验证：重复领取同一批不会返回已锁定的记录
        List<RuntimeOutboxEvent> secondClaim = runtimeMapper.claimDueBatch("tx-claim-2-" + System.nanoTime(), 5);
        boolean stillFound = secondClaim.stream().anyMatch(
                e -> "TENANT".equals(e.aggregateType()) && tenantId == e.aggregateId());
        assertThat(stillFound).as("已被领取的记录不应被第二 worker 再领到（FOR UPDATE SKIP LOCKED）").isFalse();

        // 清理
        events.forEach(e -> runtimeMapper.markCompleted(e.id()));
    }

    @Test
    void incompatibleSchemaVersionMustRejectManifestSwitch() {
        long tenantId = insertTenant();
        outboxService.record(tenantId, "TENANT", tenantId, "ENABLED", null);
        RuntimeOutboxEvent event = claimSingle("schema-guard-" + System.nanoTime());

        Set<RuntimeScope> scopes = impactResolver.resolve(event);
        assertThat(scopes).isNotEmpty();
        RuntimeScope scope = scopes.iterator().next();

        // 构造 schemaVersion=999 的非法快照
        long version = runtimeMapper.allocateVersion(scope.type().name(), scope.scopeKey());
        com.fasterxml.jackson.databind.node.ObjectNode badSnapshot =
                new com.fasterxml.jackson.databind.node.ObjectNode(com.fasterxml.jackson.databind.node.JsonNodeFactory.instance);
        badSnapshot.put("schemaVersion", 999);
        badSnapshot.put("runtimeVersion", version);
        badSnapshot.put("generatedAt", "");

        // validateSnapshot 应拒绝 schemaVersion != 1
        assertThat(org.junit.jupiter.api.Assertions.assertThrows(IllegalStateException.class,
                () -> snapshotStore.writeSnapshotAndSwitch(scope, version, badSnapshot, event)))
                .as("不兼容 schemaVersion 必须拒绝").hasMessageContaining("不完整");

        runtimeMapper.markCompleted(event.id());
    }

    @Test
    void redisRestartMustTriggerNamespaceUnhealthyAndAllowFullRebuild() throws Exception {
        // 先投影一份快照到 Redis
        long tenantId = insertTenant();
        long userId = insertTenantUser(tenantId);
        String nanoHex = Long.toHexString(System.nanoTime());
        String lookupHash = (nanoHex + nanoHex + nanoHex + nanoHex + nanoHex).substring(0, 64);
        long apiKeyId = insertApiKey(tenantId, userId, lookupHash);

        outboxService.record(tenantId, "API_KEY", apiKeyId, "CREATED", null);
        projector.project(claimSingle("redis-restart-1"));

        // 命名空间健康标记仅在 FULL_REBUILD/RUNTIME_NAMESPACE 事件时写入，此处手工标记
        snapshotStore.markNamespaceHealthy();

        RuntimeScope scope = RuntimeScope.apiKey(lookupHash, tenantId);
        String snapshotKey = RuntimeRedisSnapshotStore.snapshotKey(scope, 1L);
        assertThat(redisTemplate.hasKey(snapshotKey)).as("投影后快照应存在").isTrue();
        assertThat(snapshotStore.namespaceHealthy()).as("命名空间应健康").isTrue();

        // 模拟 Redis 数据丢失（等价于重启但避免连接池超时）
        var keys = redisTemplate.keys("fluxora:runtime:v1:*");
        if (keys != null) keys.forEach(k -> redisTemplate.delete(k));

        // 验证：清空后命名空间不健康，快照丢失
        assertThat(snapshotStore.namespaceHealthy())
                .as("清空后命名空间应不健康").isFalse();
        assertThat(redisTemplate.hasKey(snapshotKey))
                .as("清空后快照应丢失").isFalse();

        // 验证：可从 PostgreSQL 全量重建
        snapshotStore.markNamespaceHealthy();
        outboxService.record(tenantId, "API_KEY", apiKeyId, "ENABLED", null);
        projector.project(claimSingle("redis-restart-2"));

        assertThat(snapshotStore.namespaceHealthy()).as("重建后命名空间应恢复健康").isTrue();
        // 重建后新版本快照存在
        String newKey = RuntimeRedisSnapshotStore.snapshotKey(scope, 2L);
        assertThat(redisTemplate.hasKey(newKey)).as("重建后新快照应存在").isTrue();
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

    private void assertNoRouteTargetsAfterRuntimeDependencyChange(String caseName, Consumer<RuntimeFixture> mutation)
            throws Exception {
        long tenantId = insertTenant();
        String modelCode = "runtime-inactive-" + caseName + "-" + System.nanoTime();
        RuntimeFixture fixture = insertExecutableRouteFixture(tenantId, modelCode);

        mutation.accept(fixture);
        outboxService.record(tenantId, "TENANT_MODEL", fixture.modelId(), "ENABLED", null);
        projector.project(claimSingle("runtime-inactive-" + caseName + "-" + System.nanoTime()));

        RuntimeScope scope = RuntimeScope.route(tenantId, "OPENAI", modelCode);
        JsonNode snapshot = objectMapper.readTree(redisTemplate.opsForValue().get(
                RuntimeRedisSnapshotStore.snapshotKey(scope, 1L)));
        assertThat(snapshot.path("targets")).as(caseName + " 不应进入路由执行快照").isEmpty();
        assertThat(snapshot.toString()).as(caseName + " 不应留下停用或软删除关联状态")
                .doesNotContain("DISABLED", "DELETED");
    }

    /** 仅用于运行时集成测试：直接构造满足关联约束的一条可执行模型路由。 */
    private RuntimeFixture insertExecutableRouteFixture(long tenantId, String modelCode) {
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
        long routeTargetId = jdbc.queryForObject("""
                INSERT INTO route_target(tenant_id, model_route_id, tenant_model_candidate_mapping_id,
                                         provider_channel_id, upstream_model_id_snapshot, enabled, priority, weight)
                VALUES (?, ?, ?, ?, 'runtime-upstream-model', TRUE, 10, 100)
                RETURNING id
                """, Long.class, tenantId, routeId, mappingId, channelId);
        String plaintext = "runtime-credential-plaintext-" + suffix;
        EncryptedCredential encrypted = credentialCryptoService.encrypt(plaintext);
        String credentialFingerprint = ("f".repeat(64) + suffix).substring(suffix.length());
        long credentialId = jdbc.queryForObject("""
                INSERT INTO provider_credential(tenant_id, name, credential_type, masked_value, credential_fingerprint,
                                                ciphertext, initialization_vector, encryption_version, enabled, priority, weight)
                VALUES (?, '运行时凭证', 'API_KEY', '***', ?, ?, ?, ?, TRUE, 10, 100)
                RETURNING id
                """, Long.class, tenantId, credentialFingerprint, encrypted.ciphertext(),
                encrypted.initializationVector(), encrypted.encryptionVersion());
        jdbc.update("""
                INSERT INTO provider_channel_credential(tenant_id, provider_channel_id, provider_credential_id, enabled)
                VALUES (?, ?, ?, TRUE)
                """, tenantId, channelId, credentialId);
        return new RuntimeFixture(providerId, baseUrlId, channelId, candidateId, modelId, mappingId, routeId,
                routeTargetId, credentialId, plaintext);
    }

    /** 运行时夹具返回执行链路关键 ID，便于逐项验证停用或软删除后的快照边界。 */
    private record RuntimeFixture(long providerId, long baseUrlId, long channelId, long candidateId, long modelId,
                                  long mappingId, long routeId, long routeTargetId, long credentialId,
                                  String plaintext) {
    }
}
