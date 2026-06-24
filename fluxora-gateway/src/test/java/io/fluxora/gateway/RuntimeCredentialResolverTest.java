package io.fluxora.gateway;

import io.fluxora.common.security.RuntimeCredentialCipher;
import io.fluxora.gateway.credential.RuntimeCredentialResolver;
import io.fluxora.gateway.runtime.RuntimeL1Caches;
import io.fluxora.gateway.runtime.RuntimeScopeType;
import io.fluxora.gateway.runtime.RuntimeSnapshot;
import io.fluxora.gateway.runtime.RuntimeSnapshotSource;
import io.vertx.core.Future;
import io.vertx.core.MultiMap;
import io.vertx.core.json.JsonObject;
import java.time.Duration;
import java.util.Base64;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Gateway 凭证读取测试：只使用内存快照源，证明不需要 PostgreSQL 或明文缓存。 */
class RuntimeCredentialResolverTest {
    private static final String RUNTIME_KEY = "cnVudGltZS1jcmVkZW50aWFsLWtleS0wMTIzNDU2Nzg=";

    @Test
    void validCredentialSnapshotMustDecryptOnlyForCurrentRequest() throws Exception {
        MemorySource source = new MemorySource();
        source.put(RuntimeScopeType.valueOf("UPSTREAM_CREDENTIAL"), "7:11", credentialSnapshot(7, 11, 3, "BEARER", "upstream-secret"));
        RuntimeL1Caches caches = new RuntimeL1Caches(source, config(), new GatewayMetrics());
        RuntimeCredentialResolver resolver = new RuntimeCredentialResolver(caches, RUNTIME_KEY);

        RuntimeCredentialResolver.ResolvedCredential credential = await(resolver.resolve(7, 11, 3, "BEARER"));

        assertEquals("upstream-secret", credential.value());
        assertEquals("BEARER", credential.authType());
        assertTrue(caches.peek(RuntimeScopeType.valueOf("UPSTREAM_CREDENTIAL"), "7:11").payload().toString()
                .contains("encryptedCredentialPayload"));
        assertTrue(caches.peek(RuntimeScopeType.valueOf("UPSTREAM_CREDENTIAL"), "7:11").payload().toString()
                .contains("upstream-secret") == false);
    }

    @Test
    void mismatchedCredentialVersionMustFailClosed() {
        MemorySource source = new MemorySource();
        source.put(RuntimeScopeType.valueOf("UPSTREAM_CREDENTIAL"), "7:11", credentialSnapshot(7, 11, 4, "BEARER", "upstream-secret"));
        RuntimeCredentialResolver resolver = new RuntimeCredentialResolver(new RuntimeL1Caches(source, config(), new GatewayMetrics()), RUNTIME_KEY);

        Exception error = assertThrows(Exception.class, () -> await(resolver.resolve(7, 11, 3, "BEARER")));

        assertTrue(error.getMessage() == null || !error.getMessage().contains("upstream-secret"));
    }

    @Test
    void authenticationInjectionMustRemoveClientCredentialHeaders() {
        MultiMap headers = MultiMap.caseInsensitiveMultiMap()
                .add("Authorization", "Bearer client-key")
                .add("x-api-key", "client-key")
                .add("Host", "client-host");

        RuntimeCredentialResolver.applyAuthentication(headers,
                new RuntimeCredentialResolver.ResolvedCredential(11, 3, "BEARER", "gateway-key"));
        assertEquals("Bearer gateway-key", headers.get("Authorization"));
        assertEquals(null, headers.get("x-api-key"));
        assertEquals(null, headers.get("Host"));

        RuntimeCredentialResolver.applyAuthentication(headers,
                new RuntimeCredentialResolver.ResolvedCredential(11, 3, "X_API_KEY", "anthropic-key"));
        assertEquals(null, headers.get("Authorization"));
        assertEquals("anthropic-key", headers.get("x-api-key"));

        RuntimeCredentialResolver.applyAuthentication(headers,
                new RuntimeCredentialResolver.ResolvedCredential(11, 3, "NONE", null));
        assertEquals(null, headers.get("Authorization"));
        assertEquals(null, headers.get("x-api-key"));
    }

    private JsonObject credentialSnapshot(long tenantId, long credentialId, long version, String authType, String plaintext) {
        RuntimeCredentialCipher.EncryptedPayload encrypted = RuntimeCredentialCipher.encrypt(
                Base64.getDecoder().decode(RUNTIME_KEY), plaintext,
                tenantId + ":" + credentialId + ":" + version);
        return new JsonObject().put("schemaVersion", 1).put("runtimeVersion", 1L)
                .put("generatedAt", "2026-01-01T00:00:00Z").put("tenantId", tenantId)
                .put("providerCredentialId", credentialId).put("credentialVersion", version)
                .put("authType", authType).put("credentialStatus", "ENABLED").put("enabled", true)
                .put("encryptedCredentialPayload", new JsonObject()
                        .put("ciphertext", encrypted.ciphertext())
                        .put("initializationVector", encrypted.initializationVector())
                        .put("encryptionVersion", encrypted.encryptionVersion()));
    }

    private GatewayRuntimeConfig config() {
        return new GatewayRuntimeConfig("redis://unused", "unused", "api-key-test-secret",
                Duration.ofSeconds(5), Duration.ofSeconds(5), Duration.ofSeconds(5), Duration.ofSeconds(5),
                Duration.ofSeconds(2), 100);
    }

    private static <T> T await(Future<T> future) throws Exception {
        return future.toCompletionStage().toCompletableFuture().get();
    }

    private static final class MemorySource implements RuntimeSnapshotSource {
        private final Map<String, RuntimeSnapshot> snapshots = new ConcurrentHashMap<>();

        void put(RuntimeScopeType type, String key, JsonObject payload) {
            snapshots.put(type + "|" + key, new RuntimeSnapshot(type, key, 1L, payload));
        }

        @Override
        public Future<RuntimeSnapshot> load(RuntimeScopeType type, String key) {
            RuntimeSnapshot snapshot = snapshots.get(type + "|" + key);
            return snapshot == null ? Future.failedFuture(GatewayFailure.runtimeUnavailable()) : Future.succeededFuture(snapshot);
        }

        @Override
        public Future<Long> manifestVersion(RuntimeScopeType type, String key) {
            RuntimeSnapshot snapshot = snapshots.get(type + "|" + key);
            return snapshot == null ? Future.failedFuture(GatewayFailure.runtimeUnavailable())
                    : Future.succeededFuture(snapshot.runtimeVersion());
        }
    }
}
