package io.fluxora.platform.observability;

import io.fluxora.platform.runtime.availability.UpstreamRuntimeFailureService;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.connection.stream.Consumer;
import org.springframework.data.redis.connection.stream.MapRecord;
import org.springframework.data.redis.connection.stream.ReadOffset;
import org.springframework.data.redis.connection.stream.StreamOffset;
import org.springframework.data.redis.connection.stream.StreamReadOptions;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/** Platform 消费组：数据库事务成功后才 XACK，处理失败留在 PEL 等待下次重试。 */
@Component
public class RelayEventStreamConsumer {
    private static final Logger log = LoggerFactory.getLogger(RelayEventStreamConsumer.class);
    private final StringRedisTemplate redis; private final RelayRequestLogService service;
    private final UpstreamRuntimeFailureService runtimeFailureService;
    private final String stream; private final String group; private final String consumer = "platform-" + UUID.randomUUID();
    private boolean groupReady;
    private int consecutiveFailures;

    public RelayEventStreamConsumer(StringRedisTemplate redis, RelayRequestLogService service,
                                    UpstreamRuntimeFailureService runtimeFailureService,
                                    @Value("${fluxora.observability.stream-key:fluxora:relay-events:v1}") String stream,
                                    @Value("${fluxora.observability.consumer-group:fluxora-platform-v1}") String group) {
        this.redis=redis;this.service=service;this.runtimeFailureService=runtimeFailureService;this.stream=stream;this.group=group;
        log.info("中继观测消费组初始化：consumer={}, stream={}, group={}", consumer, stream, group);
    }

    @Scheduled(fixedDelayString="${fluxora.observability.stream-poll-delay-ms:500}")
    public void poll() {
        try {
            if (!groupReady && !ensureGroup()) return;
            // 先读 PEL 中上次处理失败的消息（ReadOffset.from("0")），再读新消息（>）。
            List<MapRecord<String,Object,Object>> rows=redis.opsForStream().read(Consumer.from(group,consumer),StreamReadOptions.empty().count(50),StreamOffset.create(stream,ReadOffset.from("0")));
            if(rows==null||rows.isEmpty()){
                rows=redis.opsForStream().read(Consumer.from(group,consumer),StreamReadOptions.empty().count(50),StreamOffset.create(stream,ReadOffset.lastConsumed()));
                if(rows==null||rows.isEmpty())return;
            }
            for(MapRecord<String,Object,Object> row:rows){
                try {
                    Map<String,String> fields=new LinkedHashMap<>(); row.getValue().forEach((k,v)->fields.put(String.valueOf(k),String.valueOf(v)));
                    if ("UPSTREAM_RUNTIME_FAILURE_DETECTED".equals(fields.get("eventType"))) runtimeFailureService.consume(fields);
                    else service.consume(fields);
                    // 事务成功提交后才 XACK，绝不能提前确认。
                    redis.opsForStream().acknowledge(group,row);
                } catch (Exception ex) {
                    // 处理失败不 XACK，留在 PEL 等待下次轮询重试；其他消息不受影响继续处理。
                    log.warn("中继事件处理失败（留待重试）: eventId={}, error={}", row.getValue().get("eventId"), rootCause(ex));
                }
            }
            consecutiveFailures=0;
        } catch(Exception ex) {
            consecutiveFailures++;
            groupReady=false;
            if(consecutiveFailures==1||consecutiveFailures%10==0) log.warn("中继观测 Stream 暂时不可消费 (连续失败 {} 次): {}", consecutiveFailures, ex.toString());
        }
    }

    /**
     * Gateway 首条事件前 Stream 可能尚不存在；此时不进入 read，而是在下次轮询继续尝试。
     * BUSYGROUP 表示另一实例已建组，可安全开始消费；其余错误都保持未就绪，避免永久 NOGROUP。
     */
    /** 重置消费组 ID 到 Stream 开头，重放全部未删除的消息（修复后恢复被跳过的事件）。 */
    public void resetToEarliest() {
        try {
            redis.execute((org.springframework.data.redis.connection.RedisConnection connection) -> {
                byte[] streamKey = stream.getBytes(java.nio.charset.StandardCharsets.UTF_8);
                byte[] groupName = group.getBytes(java.nio.charset.StandardCharsets.UTF_8);
                connection.execute("XGROUP", "SETID".getBytes(), streamKey, groupName, "0".getBytes());
                return null;
            });
            groupReady = true;
            log.info("中继观测消费组位置已重置到 Stream 开头：stream={}, group={}", stream, group);
        } catch (Exception ex) {
            log.error("消费组重置失败：stream={}, group={}, error={}", stream, group, rootCause(ex));
        }
    }

    /**
     * 消费组可能已由另一实例创建；BUSYGROUP 是正常情况，表示本实例可直接加入消费。
     * Spring Data Redis 可能将 Redis 错误包装为 RedisSystemException，需递归检查根因。
     */
    private boolean ensureGroup() {
        try {
            redis.opsForStream().createGroup(stream, ReadOffset.latest(), group);
            groupReady=true;
            log.info("中继观测消费组已创建：stream={}, group={}", stream, group);
        } catch (RuntimeException ex) {
            boolean busy = containsBusyGroup(ex);
            groupReady=busy;
            if(busy) log.info("中继观测消费组已存在：stream={}, group={}", stream, group);
            else log.error("中继观测消费组创建失败：stream={}, group={}, error={}", stream, group, rootCause(ex));
        }
        return groupReady;
    }

    /** 递归检查异常链中是否包含 BUSYGROUP（消费组已存在的正常情况）。 */
    private static boolean containsBusyGroup(Throwable ex) {
        Throwable current = ex;
        while (current != null) {
            if (current.getMessage() != null && current.getMessage().contains("BUSYGROUP")) return true;
            current = current.getCause();
        }
        return false;
    }

    private static String rootCause(Throwable ex) {
        Throwable current = ex;
        while (current.getCause() != null && current.getCause() != current) current = current.getCause();
        return current.toString();
    }
}
