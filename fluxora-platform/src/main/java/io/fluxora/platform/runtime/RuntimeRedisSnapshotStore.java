package io.fluxora.platform.runtime;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.time.Instant;
import java.util.List;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Component;

/**
 * Redis 运行时快照存储边界：先写不可变 Snapshot，再用 Lua 原子前移 Manifest，最后才发送轻量失效通知。
 */
@Component
public class RuntimeRedisSnapshotStore {
    private static final String KEY_PREFIX = "fluxora:runtime:v1";
    private static final String NAMESPACE_HEALTH_KEY = KEY_PREFIX + ":namespace-health";
    private static final DefaultRedisScript<Long> SWITCH_MANIFEST_SCRIPT = new DefaultRedisScript<>("""
            local current = redis.call('GET', KEYS[1])
            if current then
              local decoded = cjson.decode(current)
              if decoded['activeRuntimeVersion'] and tonumber(decoded['activeRuntimeVersion']) >= tonumber(ARGV[1]) then
                return 0
              end
            end
            redis.call('SET', KEYS[1], ARGV[2])
            return 1
            """, Long.class);

    private final StringRedisTemplate redisTemplate;
    private final ObjectMapper objectMapper;
    private final RuntimeProperties properties;

    public RuntimeRedisSnapshotStore(StringRedisTemplate redisTemplate, ObjectMapper objectMapper,
                                     RuntimeProperties properties) {
        this.redisTemplate = redisTemplate;
        this.objectMapper = objectMapper;
        this.properties = properties;
    }

    public boolean writeSnapshotAndSwitch(RuntimeScope scope, long runtimeVersion, JsonNode snapshot,
                                          RuntimeOutboxEvent event) {
        validateSnapshot(runtimeVersion, snapshot);
        String snapshotJson = serialize(snapshot);
        redisTemplate.opsForValue().set(snapshotKey(scope, runtimeVersion), snapshotJson);

        ObjectNode manifest = objectMapper.createObjectNode();
        manifest.put("schemaVersion", RuntimeSnapshotBuilder.SCHEMA_VERSION);
        manifest.put("activeRuntimeVersion", runtimeVersion);
        manifest.put("generatedAt", snapshot.path("generatedAt").asText());
        manifest.put("sourceOutboxId", event.id());
        Long switched = redisTemplate.execute(SWITCH_MANIFEST_SCRIPT, List.of(manifestKey(scope)),
                Long.toString(runtimeVersion), serialize(manifest));
        if (!Long.valueOf(1L).equals(switched)) {
            return false;
        }

        redisTemplate.convertAndSend(properties.getInvalidationChannel(), serialize(invalidationEvent(scope, runtimeVersion, event)));
        return true;
    }

    public boolean namespaceHealthy() {
        return Boolean.TRUE.equals(redisTemplate.hasKey(NAMESPACE_HEALTH_KEY));
    }

    public void markNamespaceHealthy() {
        redisTemplate.opsForValue().set(NAMESPACE_HEALTH_KEY, "schema=1;updatedAt=" + Instant.now());
    }

    public static String snapshotKey(RuntimeScope scope, long runtimeVersion) {
        return KEY_PREFIX + ":snapshot:" + scope.type().name() + ":" + scope.scopeKey() + ":v:" + runtimeVersion;
    }

    public static String manifestKey(RuntimeScope scope) {
        return KEY_PREFIX + ":manifest:" + scope.type().name() + ":" + scope.scopeKey();
    }

    private ObjectNode invalidationEvent(RuntimeScope scope, long runtimeVersion, RuntimeOutboxEvent event) {
        ObjectNode payload = objectMapper.createObjectNode();
        payload.put("eventId", "outbox-" + event.id() + "-v" + runtimeVersion);
        payload.put("schemaVersion", RuntimeSnapshotBuilder.SCHEMA_VERSION);
        payload.put("scopeType", scope.type().name());
        payload.put("scopeKey", scope.scopeKey());
        if (scope.tenantId() != null) {
            payload.put("tenantId", scope.tenantId());
        }
        if (scope.inboundProtocol() != null) {
            payload.put("inboundProtocol", scope.inboundProtocol());
        }
        if (scope.tenantModelCode() != null) {
            payload.put("tenantModelCode", scope.tenantModelCode());
        }
        payload.put("newRuntimeVersion", runtimeVersion);
        payload.put("occurredAt", event.occurredAt().toString());
        return payload;
    }

    private void validateSnapshot(long runtimeVersion, JsonNode snapshot) {
        if (snapshot.path("schemaVersion").asInt() != RuntimeSnapshotBuilder.SCHEMA_VERSION
                || snapshot.path("runtimeVersion").asLong() != runtimeVersion
                || snapshot.path("generatedAt").asText().isBlank()) {
            throw new IllegalStateException("运行时快照不完整，拒绝切换 Manifest");
        }
    }

    private String serialize(JsonNode value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (Exception exception) {
            throw new IllegalStateException("运行时快照序列化失败", exception);
        }
    }
}
