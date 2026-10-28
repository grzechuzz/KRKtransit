package pl.krktransit.realtime.ingestion.support;

import com.google.protobuf.InvalidProtocolBufferException;
import io.lettuce.core.Limit;
import io.lettuce.core.Range;
import io.lettuce.core.RedisClient;
import io.lettuce.core.StreamMessage;
import io.lettuce.core.api.StatefulRedisConnection;
import io.lettuce.core.api.sync.RedisCommands;
import io.lettuce.core.codec.ByteArrayCodec;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.function.Predicate;
import pl.krktransit.realtime.contracts.v1.Observation;

public final class ObservationStreams implements AutoCloseable {
    private static final byte[] PAYLOAD_FIELD = "payload".getBytes(StandardCharsets.UTF_8);
    private static final int PAGE = 10_000;
    private final RedisClient client;
    private final StatefulRedisConnection<byte[], byte[]> connection;

    public ObservationStreams(String url) {
        client = RedisClient.create(url);
        connection = client.connect(ByteArrayCodec.INSTANCE);
    }

    public static String key(String prefix, String sourceId) {
        return prefix + ":krakow:" + sourceId;
    }

    public RedisCommands<byte[], byte[]> commands() {
        return connection.sync();
    }

    public long length(String key) {
        return commands().xlen(bytes(key));
    }

    public List<Entry> read(String key) {
        var entries = new ArrayList<Entry>();
        var lower = Range.Boundary.<String>unbounded();
        List<StreamMessage<byte[], byte[]>> page;
        do {
            page = commands().xrange(bytes(key), Range.from(lower, Range.Boundary.unbounded()), Limit.from(PAGE));
            page.forEach(message -> entries.add(entry(message)));
            if (!page.isEmpty()) lower = Range.Boundary.excluding(page.getLast().getId());
        } while (page.size() == PAGE);
        return entries;
    }

    public List<Entry> readUntil(List<String> keys, Predicate<List<Entry>> done, Duration timeout) {
        long deadline = System.nanoTime() + timeout.toNanos();
        List<Entry> entries = readAll(keys);
        while (!done.test(entries) && System.nanoTime() < deadline) {
            pause();
            entries = readAll(keys);
        }
        return entries;
    }

    public List<Entry> readAll(List<String> keys) {
        return keys.stream().flatMap(key -> read(key).stream()).toList();
    }

    private static void pause() {
        try {
            Thread.sleep(200);
        } catch (InterruptedException failure) {
            Thread.currentThread().interrupt();
            throw new AssertionError(failure);
        }
    }

    private static Entry entry(StreamMessage<byte[], byte[]> message) {
        var key = new String(message.getStream(), StandardCharsets.UTF_8);
        return new Entry(key, message.getId(), decode(payload(message.getBody())));
    }

    private static byte[] payload(Map<byte[], byte[]> body) {
        if (body.size() != 1) throw new AssertionError("Expected exactly one field, got " + body.size());
        var field = body.entrySet().iterator().next();
        if (!Arrays.equals(field.getKey(), PAYLOAD_FIELD)) throw new AssertionError("Unexpected stream field");
        return field.getValue();
    }

    public static Observation decode(byte[] payload) {
        try {
            return Observation.parseFrom(payload);
        } catch (InvalidProtocolBufferException failure) {
            throw new AssertionError(failure);
        }
    }

    private static byte[] bytes(String value) {
        return value.getBytes(StandardCharsets.UTF_8);
    }

    @Override
    public void close() {
        connection.close();
        client.shutdown();
    }

    public record Entry(String stream, String id, Observation observation) {
        public long appendMillis() {
            return Long.parseLong(id.substring(0, id.indexOf('-')));
        }

        public String tripId() {
            return observation.hasVehiclePosition() ? observation.getVehiclePosition().getTripId()
                    : observation.getTripUpdate().getTripId();
        }
    }
}
