package pl.krktransit.stopevents.adapter.inbound.stream;

import com.google.protobuf.InvalidProtocolBufferException;
import io.lettuce.core.RedisCommandExecutionException;
import io.lettuce.core.RedisException;
import io.lettuce.core.StreamMessage;
import io.lettuce.core.XReadArgs;
import io.lettuce.core.api.StatefulRedisConnection;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Supplier;
import pl.krktransit.stopevents.application.port.ObservationSource;
import pl.krktransit.stopevents.application.port.StorageFailure;
import pl.krktransit.stopevents.application.processing.SourceBatch;
import pl.krktransit.stopevents.application.processing.StreamEntry;
import pl.krktransit.stopevents.domain.source.SourceId;

public final class StreamConsumer implements ObservationSource, AutoCloseable {
    private static final System.Logger LOG = System.getLogger(StreamConsumer.class.getName());
    private final Supplier<StatefulRedisConnection<String, byte[]>> connections;
    private final Map<SourceId, String> streams;
    private final ObservationWireMapper mapper = new ObservationWireMapper();
    private final Set<SourceId> reportedGaps = new HashSet<>();
    private final int count;
    private final Duration block;
    private StatefulRedisConnection<String, byte[]> connection;
    private long malformedEntries;

    public StreamConsumer(Supplier<StatefulRedisConnection<String, byte[]>> connections, String prefix,
                          Set<SourceId> sources, int count, Duration block) {
        this.connections = connections;
        var configured = new LinkedHashMap<SourceId, String>();
        sources.stream().sorted(Comparator.comparing(SourceId::toString))
                .forEach(source -> configured.put(source, prefix + ":" + source));
        this.streams = Collections.unmodifiableMap(configured);
        this.count = count;
        this.block = block;
    }

    @Override
    public String tail(SourceId source) {
        try {
            var info = info(source);
            return info.isEmpty() ? "0-0" : text(info.get("last-generated-id"));
        } catch (RedisException failure) { throw unavailable(failure); }
    }

    @Override
    public List<SourceBatch> read(Map<SourceId, String> cursors) {
        if (cursors.isEmpty()) return List.of();
        try {
            checkGaps(cursors);
            var messages = connection().sync().xread(XReadArgs.Builder.count(count).block(block), offsets(cursors));
            return batches(messages);
        } catch (RedisException failure) { throw unavailable(failure); }
    }

    @SuppressWarnings("unchecked")
    private XReadArgs.StreamOffset<String>[] offsets(Map<SourceId, String> cursors) {
        return streams.entrySet().stream().filter(entry -> cursors.containsKey(entry.getKey()))
                .map(entry -> XReadArgs.StreamOffset.from(entry.getValue(), cursors.get(entry.getKey())))
                .toArray(XReadArgs.StreamOffset[]::new);
    }

    private List<SourceBatch> batches(List<StreamMessage<String, byte[]>> messages) {
        var grouped = new LinkedHashMap<SourceId, List<StreamEntry>>();
        if (messages == null) return List.of();
        for (var message : messages) {
            var source = sourceFor(message.getStream());
            grouped.computeIfAbsent(source, ignored -> new ArrayList<>()).add(decode(source, message));
        }
        return grouped.entrySet().stream().map(entry -> new SourceBatch(entry.getKey(), entry.getValue())).toList();
    }

    private StreamEntry decode(SourceId source, StreamMessage<String, byte[]> message) {
        try {
            return new StreamEntry(message.getId(), mapper.decode(source, message.getBody().get("payload")));
        } catch (InvalidProtocolBufferException | IllegalArgumentException failure) {
            long count = ++malformedEntries;
            var level = count % 1000 == 1 ? System.Logger.Level.WARNING : System.Logger.Level.DEBUG;
            LOG.log(level, "Skipping malformed observation: source={0}, id={1}, reason={2}, entries={3}",
                    source, message.getId(), failure.getMessage(), count);
            return new StreamEntry(message.getId(), null);
        }
    }

    private SourceId sourceFor(String stream) {
        return streams.entrySet().stream().filter(entry -> entry.getValue().equals(stream))
                .map(Map.Entry::getKey).findFirst().orElseThrow();
    }

    private void checkGaps(Map<SourceId, String> cursors) {
        cursors.forEach((source, cursor) -> {
            boolean gap = trimmedPast(source, cursor);
            if (gap && reportedGaps.add(source)) {
                LOG.log(System.Logger.Level.WARNING, "Possible observation gap: source={0}, cursor={1}", source, cursor);
            }
            if (!gap) reportedGaps.remove(source);
        });
    }

    private boolean trimmedPast(SourceId source, String cursor) {
        if (cursor.equals("0-0")) return false;
        var first = info(source).get("first-entry");
        return first instanceof List<?> entry && !entry.isEmpty() && newer(text(entry.getFirst()), cursor);
    }

    private Map<String, Object> info(SourceId source) {
        List<Object> values;
        try { values = connection().sync().xinfoStream(streams.get(source)); }
        catch (RedisCommandExecutionException failure) {
            if (failure.getMessage().contains("no such key")) return Map.of();
            throw failure;
        }
        var fields = new HashMap<String, Object>();
        for (int i = 0; i < values.size(); i += 2) fields.put(text(values.get(i)), values.get(i + 1));
        return fields;
    }

    private boolean newer(String first, String cursor) {
        var left = first.split("-");
        var right = cursor.split("-");
        int milliseconds = Long.compareUnsigned(Long.parseUnsignedLong(left[0]), Long.parseUnsignedLong(right[0]));
        return milliseconds > 0 || (milliseconds == 0 && Long.compareUnsigned(
                Long.parseUnsignedLong(left[1]), Long.parseUnsignedLong(right[1])) > 0);
    }

    private String text(Object value) {
        return value instanceof byte[] bytes ? new String(bytes, StandardCharsets.UTF_8) : value.toString();
    }

    private StatefulRedisConnection<String, byte[]> connection() {
        if (connection == null) connection = connections.get();
        return connection;
    }

    private StorageFailure unavailable(RedisException failure) {
        close();
        return new StorageFailure(StorageFailure.Kind.REDIS, "Observation Redis is unavailable", failure);
    }

    @Override
    public void close() {
        if (connection != null) connection.close();
        connection = null;
    }
}
