package io.fluxora.platform.observability;

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

/** Platform 消费组：数据库事务成功后才 XACK；失败消息保留在 Pending 等待后续恢复。 */
@Component
public class RelayEventStreamConsumer {
    private static final Logger log = LoggerFactory.getLogger(RelayEventStreamConsumer.class);
    private final StringRedisTemplate redis; private final RelayRequestLogService service;
    private final String stream; private final String group; private final String consumer = "platform-" + UUID.randomUUID();
    private boolean groupReady;
    public RelayEventStreamConsumer(StringRedisTemplate redis, RelayRequestLogService service,
                                    @Value("${fluxora.observability.stream-key:fluxora:relay-events:v1}") String stream,
                                    @Value("${fluxora.observability.consumer-group:fluxora-platform-v1}") String group) {
        this.redis=redis;this.service=service;this.stream=stream;this.group=group;
    }
    @Scheduled(fixedDelayString="${fluxora.observability.stream-poll-delay-ms:500}")
    public void poll() {
        try {
            if (!groupReady) { try { redis.opsForStream().createGroup(stream, ReadOffset.latest(), group); } catch (RuntimeException ignored) { } groupReady=true; }
            List<MapRecord<String,Object,Object>> rows=redis.opsForStream().read(Consumer.from(group,consumer),StreamReadOptions.empty().count(50),StreamOffset.create(stream,ReadOffset.lastConsumed()));
            if(rows==null)return;
            for(MapRecord<String,Object,Object> row:rows){
                Map<String,String> fields=new LinkedHashMap<>(); row.getValue().forEach((k,v)->fields.put(String.valueOf(k),String.valueOf(v)));
                // 每个 XACK 都严格落在对应 PostgreSQL 事务提交之后，不能因批次中其他消息失败提前确认。
                service.consume(fields); redis.opsForStream().acknowledge(group,row);
            }
        } catch(Exception ex) { log.warn("中继观测 Stream 暂时不可消费，将保留 Pending 后重试"); }
    }
}
