package io.fluxora.gateway.credential;

import io.fluxora.common.security.RuntimeCredentialCipher;
import io.fluxora.gateway.GatewayFailure;
import io.fluxora.gateway.runtime.RuntimeL1Caches;
import io.vertx.core.Future;
import io.vertx.core.MultiMap;
import io.vertx.core.json.JsonObject;
import java.util.Base64;

/**
 * Gateway 的上游凭证读取边界。
 * 该类只从 Redis 敏感运行时 Scope 读取密文，按本次请求的版本和 AAD 临时解密；
 * 不访问 PostgreSQL，也不把明文写入 L1、日志、异常或静态字段。
 */
public final class RuntimeCredentialResolver {
    private final RuntimeL1Caches caches;
    private final byte[] runtimeKey;

    public RuntimeCredentialResolver(RuntimeL1Caches caches, String runtimeCredentialKey) {
        this.caches = caches;
        this.runtimeKey = decodeKey(runtimeCredentialKey);
    }

    public Future<ResolvedCredential> resolve(long tenantId, long credentialId, long expectedVersion,
                                              String expectedAuthType) {
        return caches.credential(tenantId, credentialId).map(snapshot -> {
            JsonObject payload = snapshot.payload();
            if (payload.getLong("tenantId", -1L) != tenantId
                    || payload.getLong("providerCredentialId", -1L) != credentialId
                    || payload.getLong("credentialVersion", -1L) != expectedVersion
                    || !expectedAuthType.equals(payload.getString("authType"))
                    || !payload.getBoolean("enabled", false)
                    || !"ENABLED".equals(payload.getString("credentialStatus"))) {
                throw GatewayFailure.runtimeUnavailable();
            }
            if ("NONE".equals(expectedAuthType)) {
                return new ResolvedCredential(credentialId, expectedVersion, expectedAuthType, null);
            }
            JsonObject encrypted = payload.getJsonObject("encryptedCredentialPayload");
            if (encrypted == null) {
                throw GatewayFailure.runtimeUnavailable();
            }
            String value = RuntimeCredentialCipher.decrypt(runtimeKey, new RuntimeCredentialCipher.EncryptedPayload(
                    encrypted.getString("ciphertext"), encrypted.getString("initializationVector"),
                    encrypted.getString("encryptionVersion")), aad(tenantId, credentialId, expectedVersion));
            if (value.isBlank()) {
                throw GatewayFailure.runtimeUnavailable();
            }
            return new ResolvedCredential(credentialId, expectedVersion, expectedAuthType, value);
        }).recover(error -> Future.failedFuture(error instanceof GatewayFailure
                ? error : GatewayFailure.runtimeUnavailable()));
    }

    /**
     * 认证头注入前先清除客户端认证、Host 与 hop-by-hop Header。
     * 这确保 Fluxora API Key 和调用方自带第三方凭证不可能被转发到上游。
     */
    public static void applyAuthentication(MultiMap headers, ResolvedCredential credential) {
        headers.remove("Authorization").remove("x-api-key").remove("Host").remove("Connection")
                .remove("Keep-Alive").remove("Proxy-Authenticate").remove("Proxy-Authorization")
                .remove("TE").remove("Trailer").remove("Transfer-Encoding").remove("Upgrade")
                .remove("Content-Length");
        switch (credential.authType()) {
            case "BEARER" -> headers.set("Authorization", "Bearer " + requireValue(credential));
            case "X_API_KEY" -> headers.set("x-api-key", requireValue(credential));
            case "NONE" -> {
                // 无认证上游不写任何凭证 Header，仍保留上面的客户端头剥离。
            }
            default -> throw GatewayFailure.runtimeUnavailable();
        }
    }

    private static String requireValue(ResolvedCredential credential) {
        if (credential.value() == null || credential.value().isBlank()) {
            throw GatewayFailure.runtimeUnavailable();
        }
        return credential.value();
    }

    private static byte[] decodeKey(String configuredKey) {
        try {
            byte[] decoded = Base64.getDecoder().decode(configuredKey);
            if (decoded.length != 32) {
                throw new IllegalArgumentException("invalid key length");
            }
            return decoded;
        } catch (IllegalArgumentException error) {
            throw new IllegalStateException("运行时凭证安全配置无效");
        }
    }

    private static String aad(long tenantId, long credentialId, long credentialVersion) {
        return tenantId + ":" + credentialId + ":" + credentialVersion;
    }

    /** 当前请求临时持有的上游凭证，调用方不得缓存或序列化该对象。 */
    public record ResolvedCredential(long providerCredentialId, long credentialVersion, String authType, String value) {
    }
}
