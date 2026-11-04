package pl.krktransit.livevehicles.bootstrap;

import java.time.Duration;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;
import org.springframework.boot.context.properties.ConfigurationProperties;
import pl.krktransit.livevehicles.domain.SourceId;

@ConfigurationProperties("live-vehicles")
public record LiveVehiclesProperties(boolean jobsEnabled, List<Source> sources, String streamPrefix,
                                     int readCount, Duration readBlock, Duration retention, int maximumVehicles,
                                     Duration forecastRetention, int maximumForecasts, Duration retryDelay, Duration shutdownTimeout, String grpcToken,
                                     Redis redis) {
    public LiveVehiclesProperties {
        sources = List.copyOf(sources);
        requireSources(sources);
        positive(readBlock, retention, forecastRetention, retryDelay, shutdownTimeout);
        requireStreamSettings(readCount, maximumVehicles, maximumForecasts, streamPrefix);
        requireBlockingTimeout(redis.commandTimeout(), readBlock);
        grpcToken = grpcToken == null ? "" : grpcToken;
    }

    public Set<SourceId> sourceIds() { return sources.stream().map(Source::id).collect(Collectors.toUnmodifiableSet()); }

    public Duration replayWindow() { return retention.compareTo(forecastRetention) >= 0 ? retention : forecastRetention; }

    @Override
    public String toString() { return "LiveVehiclesProperties[sources=" + sources + "]"; }

    private static void requireSources(List<Source> sources) {
        if (!validSources(sources)) throw new IllegalArgumentException("Sources must be nonempty and unique");
    }

    private static boolean validSources(List<Source> sources) {
        return !sources.isEmpty() && sources.stream().map(Source::id).distinct().count() == sources.size();
    }

    private static void requireStreamSettings(int readCount, int maximumVehicles, int maximumForecasts, String streamPrefix) {
        if (!validStreamSettings(readCount, maximumVehicles, maximumForecasts, streamPrefix)) {
            throw new IllegalArgumentException("Positive read count, capacities and a stream prefix are required");
        }
    }

    private static boolean validStreamSettings(int readCount, int maximumVehicles, int maximumForecasts, String streamPrefix) {
        return readCount > 0 && maximumVehicles > 0 && maximumForecasts > 0 && hasText(streamPrefix);
    }

    private static void requireBlockingTimeout(Duration commandTimeout, Duration readBlock) {
        if (commandTimeout.compareTo(readBlock) <= 0) {
            throw new IllegalArgumentException("Redis command timeout must exceed the blocking read duration");
        }
    }

    private static void positive(Duration... values) {
        for (var value : values) {
            if (!isPositive(value)) throw new IllegalArgumentException("Durations must be positive");
        }
    }

    private static boolean isPositive(Duration value) { return value != null && value.isPositive(); }

    private static boolean hasText(String value) { return value != null && !value.isBlank(); }

    private static void requireRedisUrl(String url) {
        if (!hasText(url)) throw new IllegalArgumentException("Redis URL is required");
    }

    public record Source(String cityId, String sourceId) {
        public Source { new SourceId(cityId, sourceId); }
        public SourceId id() { return new SourceId(cityId, sourceId); }
    }

    public record Redis(String url, String username, String password, String clientName,
                        Duration connectTimeout, Duration commandTimeout) {
        public Redis {
            positive(connectTimeout, commandTimeout);
            requireRedisUrl(url);
        }
        @Override
        public String toString() { return "Redis[clientName=" + clientName + "]"; }
    }
}
