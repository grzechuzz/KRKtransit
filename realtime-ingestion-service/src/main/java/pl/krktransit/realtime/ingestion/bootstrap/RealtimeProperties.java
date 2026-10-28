package pl.krktransit.realtime.ingestion.bootstrap;

import java.net.URI;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;
import org.springframework.boot.context.properties.ConfigurationProperties;
import pl.krktransit.realtime.ingestion.domain.source.Endpoint;
import pl.krktransit.realtime.ingestion.domain.source.FeedKind;
import pl.krktransit.realtime.ingestion.domain.source.SourceId;

@ConfigurationProperties("realtime")
public record RealtimeProperties(List<Source> sources, boolean jobsEnabled, Duration pollDelay,
                                 List<Duration> failureDelays, double failureJitter, Duration shutdownTimeout,
                                 Duration reportInterval, Http http, Redis redis) {
    public RealtimeProperties {
        sources = List.copyOf(sources);
        failureDelays = List.copyOf(failureDelays);
        validateSources(sources);
        if (!hasValidIntervals(shutdownTimeout, reportInterval)) {
            throw new IllegalArgumentException("Realtime shutdown and report intervals must be positive");
        }
    }

    private static void validateSources(List<Source> sources) {
        if (!hasSources(sources)) {
            throw new IllegalArgumentException("At least one realtime source is required");
        }
        if (!hasUniqueSources(sources)) {
            throw new IllegalArgumentException("Realtime source identities must be unique");
        }
    }

    private static boolean hasSources(List<Source> sources) {
        return !sources.isEmpty();
    }

    private static boolean hasUniqueSources(List<Source> sources) {
        return sources.stream().map(Source::id).distinct().count() == sources.size();
    }

    private static boolean hasValidIntervals(Duration shutdownTimeout, Duration reportInterval) {
        return shutdownTimeout.isPositive() && reportInterval.isPositive();
    }

    public List<Endpoint> endpoints() {
        return sources.stream().flatMap(Source::endpoints).toList();
    }

    public Map<SourceId, Long> streamMaxLengths() {
        var maxLengths = new LinkedHashMap<SourceId, Long>();
        sources.forEach(source -> maxLengths.put(source.id(), source.streamMaxLength()));
        return maxLengths;
    }

    public record Source(String cityId, String sourceId, long streamMaxLength, URI vehiclePositionsUrl,
                         URI tripUpdatesUrl) {
        SourceId id() { return new SourceId(cityId, sourceId); }

        Stream<Endpoint> endpoints() {
            return Stream.of(new Endpoint(id(), FeedKind.VEHICLE_POSITIONS, vehiclePositionsUrl),
                    new Endpoint(id(), FeedKind.TRIP_UPDATES, tripUpdatesUrl));
        }
    }

    public record Http(Duration connectTimeout, Duration downloadTimeout, int maxConcurrent, int maxPerHost,
                       int maxBodyBytes) {
        public Http {
            if (!hasValidTimeouts(connectTimeout, downloadTimeout)) {
                throw new IllegalArgumentException("Realtime HTTP timeouts must be positive");
            }
            if (!hasValidLimits(maxConcurrent, maxPerHost, maxBodyBytes)) {
                throw new IllegalArgumentException("Realtime HTTP limits must be positive");
            }
        }

        private static boolean hasValidTimeouts(Duration connectTimeout, Duration downloadTimeout) {
            return connectTimeout.isPositive() && downloadTimeout.isPositive();
        }

        private static boolean hasValidLimits(int maxConcurrent, int maxPerHost, int maxBodyBytes) {
            return maxConcurrent > 0 && maxPerHost > 0 && maxBodyBytes > 0;
        }
    }

    public record Redis(String url, String username, String password, String clientName, String streamPrefix,
                        Duration connectTimeout, Duration commandTimeout, int maxInFlight,
                        long publicationBufferBytes, Duration recoveryDelay, Duration shutdownDrain) {
        public Redis {
            if (!hasUrl(url)) {
                throw new IllegalArgumentException("Redis URL is required");
            }
            if (!hasValidPublicationLimits(maxInFlight, publicationBufferBytes)) {
                throw new IllegalArgumentException("Redis in-flight and publication buffer limits must be positive");
            }
        }

        private static boolean hasUrl(String url) {
            return url != null && !url.isBlank();
        }

        private static boolean hasValidPublicationLimits(int maxInFlight, long publicationBufferBytes) {
            return maxInFlight > 0 && publicationBufferBytes > 0;
        }

        @Override
        public String toString() {
            return "Redis[url=" + url + ", username=" + username + ", streamPrefix=" + streamPrefix + "]";
        }
    }
}
