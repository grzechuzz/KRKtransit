package pl.krktransit.schedule.bootstrap;

import java.net.URI;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import org.springframework.boot.context.properties.ConfigurationProperties;
import pl.krktransit.schedule.domain.feed.FeedConfig;
import pl.krktransit.schedule.domain.feed.FeedId;

@ConfigurationProperties("schedule")
public record ScheduleProperties(List<Source> feeds, Path archiveDirectory, Path tempDirectory,
                                 boolean jobsEnabled, Duration importInterval, Duration cleanupInterval,
                                 int cleanupBatchSize, long maxUncompressedBytes, Download download,
                                 String grpcToken, int grpcMaxConcurrentCalls) {
    public ScheduleProperties {
        feeds = List.copyOf(feeds);
        validateFeeds(feeds);
        validateJobIntervals(importInterval, cleanupInterval);
        validateStorageLimits(cleanupBatchSize, maxUncompressedBytes);
        if (grpcMaxConcurrentCalls < 1) {
            throw new IllegalArgumentException("Maximum concurrent gRPC calls must be positive");
        }
    }

    private static void validateFeeds(List<Source> feeds) {
        if (feeds.isEmpty()) throw new IllegalArgumentException("At least one schedule feed is required");
        if (feeds.stream().map(Source::id).distinct().count() != feeds.size()) {
            throw new IllegalArgumentException("Schedule feed identities must be unique");
        }
    }

    private static void validateJobIntervals(Duration importInterval, Duration cleanupInterval) {
        if (!importInterval.isPositive() || !cleanupInterval.isPositive()) {
            throw new IllegalArgumentException("Schedule job intervals must be positive");
        }
    }

    private static void validateStorageLimits(int cleanupBatchSize, long maxUncompressedBytes) {
        if (cleanupBatchSize < 1 || maxUncompressedBytes < 1) {
            throw new IllegalArgumentException("Schedule cleanup and parsing limits must be positive");
        }
    }

    public List<FeedConfig> feedConfigs() {
        return feeds.stream().map(Source::config).toList();
    }

    public record Source(String cityId, String sourceId, URI staticUrl) {
        FeedId id() { return new FeedId(cityId, sourceId); }
        FeedConfig config() { return new FeedConfig(id(), staticUrl); }
    }

    public record Download(Duration connectTimeout, Duration requestTimeout, int attempts,
                           Duration initialBackoff, Duration maxRetryDelay, long maxBytes,
                           int maxConcurrent, int maxPerHost) {
        public Download {
            validateDurations(connectTimeout, requestTimeout, initialBackoff, maxRetryDelay);
            validateLimits(attempts, maxBytes, maxConcurrent, maxPerHost);
        }

        private static void validateDurations(Duration connectTimeout, Duration requestTimeout,
                                              Duration initialBackoff, Duration maxRetryDelay) {
            if (!connectTimeout.isPositive() || !requestTimeout.isPositive()
                    || initialBackoff.isNegative() || maxRetryDelay.isNegative()) {
                throw new IllegalArgumentException("Invalid schedule download durations");
            }
        }

        private static void validateLimits(int attempts, long maxBytes, int maxConcurrent, int maxPerHost) {
            if (attempts < 1 || maxBytes < 1 || maxConcurrent < 1 || maxPerHost < 1) {
                throw new IllegalArgumentException("Schedule download limits must be positive");
            }
        }
    }
}
