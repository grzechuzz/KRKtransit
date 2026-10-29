package pl.krktransit.stopevents.bootstrap;

import java.time.Duration;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;
import org.springframework.boot.context.properties.ConfigurationProperties;
import pl.krktransit.stopevents.domain.source.SourceId;

@ConfigurationProperties("stop-events")
public record StopEventsProperties(boolean jobsEnabled, List<Source> sources, String streamPrefix,
                                    String statePrefix, int readCount, Duration readBlock, Duration inactivity,
                                    List<Duration> failureDelays, Duration shutdownTimeout, Redis redis,
                                    Schedule schedule) {
    public StopEventsProperties {
        sources = List.copyOf(sources);
        failureDelays = List.copyOf(failureDelays);
        validateSources(sources);
        requirePositive(readBlock, inactivity, shutdownTimeout);
        validateDelays(failureDelays);
        validateStreams(readCount, streamPrefix, statePrefix);
        validateBlocking(redis, readBlock);
    }

    public Set<SourceId> sourceIds() { return sources.stream().map(Source::id).collect(Collectors.toUnmodifiableSet()); }

    private static void validateSources(List<Source> sources) {
        if (sources.isEmpty() || sources.stream().map(Source::id).distinct().count() != sources.size()) {
            throw new IllegalArgumentException("Sources must be nonempty and unique");
        }
    }

    private static void validateStreams(int readCount, String streamPrefix, String statePrefix) {
        if (readCount <= 0 || streamPrefix == null || statePrefix == null) {
            throw new IllegalArgumentException("Stream count and key prefixes are required");
        }
    }

    private static void validateBlocking(Redis redis, Duration readBlock) {
        if (redis.commandTimeout().compareTo(readBlock) <= 0) {
            throw new IllegalArgumentException("Redis command timeout must exceed the blocking read duration");
        }
    }

    private static void validateDelays(List<Duration> delays) {
        if (delays.isEmpty()) throw new IllegalArgumentException("Failure delays are required");
        delays.forEach(value -> requirePositive(value));
    }

    private static void requirePositive(Duration... values) {
        for (var value : values) {
            if (value == null || !value.isPositive()) throw new IllegalArgumentException("Durations must be positive");
        }
    }

    public record Source(String cityId, String sourceId) {
        public Source { new SourceId(cityId, sourceId); }
        public SourceId id() { return new SourceId(cityId, sourceId); }
    }

    public record Redis(String url, String username, String password, String clientName,
                        Duration connectTimeout, Duration commandTimeout) {
        public Redis {
            requirePositive(connectTimeout, commandTimeout);
            if (url == null || url.isBlank()) throw new IllegalArgumentException("Redis URL is required");
        }
        @Override
        public String toString() { return "Redis[clientName=" + clientName + "]"; }
    }

    public record Schedule(String address, String token, Duration deadline, int contextCacheSize, Duration hashRefresh) {
        public Schedule {
            requirePositive(deadline, hashRefresh);
            if (address == null || address.isBlank() || contextCacheSize <= 0) {
                throw new IllegalArgumentException("Schedule address and positive cache size are required");
            }
        }
        @Override
        public String toString() { return "Schedule[address=" + address + ", deadline=" + deadline + "]"; }
    }
}
