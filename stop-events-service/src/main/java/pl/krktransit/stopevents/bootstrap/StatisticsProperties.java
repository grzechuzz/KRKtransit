package pl.krktransit.stopevents.bootstrap;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties("stop-events.statistics")
public record StatisticsProperties(int readPoolSize, int maximumCalculations, Duration connectionWait, String grpcToken,
                                   Cache cache) {
    public StatisticsProperties {
        requireLimits(readPoolSize, maximumCalculations);
        requireDuration(connectionWait);
        grpcToken = grpcToken == null ? "" : grpcToken;
        if (cache == null) throw new IllegalArgumentException("Statistics cache settings are required");
    }

    @Override
    public String toString() {
        return "StatisticsProperties[readPoolSize=" + readPoolSize + ", maximumCalculations=" + maximumCalculations
                + ", connectionWait=" + connectionWait + "]";
    }

    private static void requireLimits(int... values) {
        for (int value : values) {
            if (value < 1) throw new IllegalArgumentException("Statistics limits must be positive");
        }
    }

    private static void requireDuration(Duration value) {
        if (!positive(value)) throw new IllegalArgumentException("Statistics durations must be positive");
    }

    private static boolean positive(Duration value) { return value != null && value.isPositive(); }

    public record Cache(String prefix, int maximumEntries, int maximumEntryBytes, Duration commandTimeout) {
        public Cache {
            requireLimits(maximumEntries, maximumEntryBytes);
            requireDuration(commandTimeout);
            if (!hasText(prefix)) throw new IllegalArgumentException("Statistics cache prefix is required");
        }

        private static boolean hasText(String value) { return value != null && !value.isBlank(); }
    }
}
