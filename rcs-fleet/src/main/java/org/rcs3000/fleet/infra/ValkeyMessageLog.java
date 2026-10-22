package org.rcs3000.fleet.infra;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;

import org.rcs3000.fleet.core.MessageLog;
import org.springframework.data.domain.Range;
import org.springframework.data.redis.connection.Limit;
import org.springframework.data.redis.connection.RedisStreamCommands.TrimOptions;
import org.springframework.data.redis.connection.RedisStreamCommands.XAddOptions;
import org.springframework.data.redis.connection.stream.MapRecord;
import org.springframework.data.redis.connection.stream.RecordId;
import org.springframework.data.redis.connection.stream.StreamRecords;
import org.springframework.data.redis.core.StringRedisTemplate;

/**
 * One Valkey stream per vehicle, {@value #KEY_PREFIX}&lt;code&gt;. Entry ids carry the time they were
 * written, so time-range queries are id ranges; every append trims entries older than the retention,
 * and a stream that receives nothing for a whole retention period expires.
 */
public class ValkeyMessageLog implements MessageLog {

    public static final String KEY_PREFIX = "rcs:fleet:messages:";

    private final StringRedisTemplate redis;
    private final Duration retention;

    public ValkeyMessageLog(StringRedisTemplate redis, Duration retention) {
        this.redis = redis;
        this.retention = retention;
    }

    @Override
    public void append(String vehicleCode, Direction direction, String topic, String payload) {
        String key = KEY_PREFIX + vehicleCode;
        long cutoff = System.currentTimeMillis() - retention.toMillis();
        MapRecord<String, String, String> record = StreamRecords.newRecord().in(key)
                .ofMap(Map.of("direction", direction.name(), "topic", topic, "payload", payload));
        redis.opsForStream().add(record, XAddOptions.trim(TrimOptions.minId(RecordId.of(cutoff, 0)).approximate()));
        redis.expire(key, retention);
    }

    @Override
    public List<Entry> find(String vehicleCode, Instant from, Instant to, int limit) {
        Range<String> range = Range.closed(from == null ? "-" : from.toEpochMilli() + "-0",
                to == null ? "+" : to.toEpochMilli() + "-" + Long.MAX_VALUE);
        List<MapRecord<String, Object, Object>> records = redis.opsForStream()
                .reverseRange(KEY_PREFIX + vehicleCode, range, Limit.limit().count(limit));
        if (records == null) {
            return List.of();
        }
        return records.stream().map(r -> new Entry(r.getId().getValue(),
                Instant.ofEpochMilli(r.getId().getTimestamp()), vehicleCode,
                Direction.valueOf((String) r.getValue().get("direction")), (String) r.getValue().get("topic"),
                (String) r.getValue().get("payload"))).toList();
    }
}
