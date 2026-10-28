package pl.krktransit.realtime.ingestion.adapter.outbound.redis;

import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Map;
import pl.krktransit.realtime.ingestion.domain.source.SourceId;

public final class StreamRoutes {
    private final Map<SourceId, StreamTarget> targets;

    public StreamRoutes(String prefix, Map<SourceId, Long> maxLengths) {
        if (!hasPrefix(prefix)) {
            throw new IllegalArgumentException("Redis stream prefix is required");
        }
        var targets = new HashMap<SourceId, StreamTarget>();
        maxLengths.forEach((source, maxLength) -> targets.put(source, target(prefix, source, maxLength)));
        this.targets = Map.copyOf(targets);
    }

    private static StreamTarget target(String prefix, SourceId source, long maxLength) {
        if (!hasValidMaxLength(maxLength)) {
            throw new IllegalArgumentException("Stream max length must be positive for " + source);
        }
        return new StreamTarget(key(prefix, source), maxLength);
    }

    private static boolean hasPrefix(String prefix) {
        return prefix != null && !prefix.isBlank();
    }

    private static boolean hasValidMaxLength(long maxLength) {
        return maxLength > 0;
    }

    static byte[] key(String prefix, SourceId source) {
        return (prefix + ":" + source).getBytes(StandardCharsets.UTF_8);
    }

    StreamTarget target(SourceId source) {
        if (!hasRoute(source)) {
            throw new IllegalArgumentException("No Redis stream is configured for " + source);
        }
        return targets.get(source);
    }

    private boolean hasRoute(SourceId source) {
        return targets.containsKey(source);
    }
}
