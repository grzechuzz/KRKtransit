package pl.krktransit.publicapi.bootstrap;

import java.time.Duration;
import java.util.List;
import org.springframework.boot.context.properties.ConfigurationProperties;
import pl.krktransit.publicapi.adapter.outbound.grpc.GrpcCallSettings;

@ConfigurationProperties("public-api")
public record PublicApiProperties(List<String> allowedOrigins, Statistics statistics, Schedule schedule,
                                  LiveVehicles liveVehicles) {
    public PublicApiProperties {
        allowedOrigins = List.copyOf(allowedOrigins);
        if (allowedOrigins.isEmpty()) throw new IllegalArgumentException("At least one allowed origin is required");
        requirePresent(statistics, schedule, liveVehicles);
    }

    private static void requirePresent(Object... sections) {
        for (var section : sections) {
            if (section == null) throw new IllegalArgumentException("Every public-api section is required");
        }
    }

    private static void requireAddress(String address) {
        if (address == null || address.isBlank()) throw new IllegalArgumentException("Upstream address is required");
    }

    private static void requirePositive(Duration... values) {
        for (var value : values) {
            if (!isPositive(value)) throw new IllegalArgumentException("Durations must be positive");
        }
    }

    private static void requirePositive(int... values) {
        for (var value : values) {
            if (value <= 0) throw new IllegalArgumentException("Limits must be positive");
        }
    }

    private static boolean isPositive(Duration value) { return value != null && value.isPositive(); }

    public record Statistics(String address, String token, Duration deadline) {
        public Statistics {
            requireAddress(address);
            requirePositive(deadline);
        }

        public GrpcCallSettings calls() { return new GrpcCallSettings(token, deadline); }

        @Override
        public String toString() { return "Statistics[address=" + address + ", deadline=" + deadline + "]"; }
    }

    public record Schedule(String address, String token, Duration deadline, int tripCacheSize, Duration hashRefresh,
                           int maximumConcurrentLookups) {
        public Schedule {
            requireAddress(address);
            requirePositive(deadline, hashRefresh);
            requirePositive(tripCacheSize, maximumConcurrentLookups);
        }

        public GrpcCallSettings calls() { return new GrpcCallSettings(token, deadline); }

        @Override
        public String toString() { return "Schedule[address=" + address + ", deadline=" + deadline + "]"; }
    }

    public record LiveVehicles(String address, String token, Duration deadline, Duration snapshotLifetime) {
        public LiveVehicles {
            requireAddress(address);
            requirePositive(deadline, snapshotLifetime);
        }

        public GrpcCallSettings calls() { return new GrpcCallSettings(token, deadline); }

        @Override
        public String toString() { return "LiveVehicles[address=" + address + ", deadline=" + deadline + "]"; }
    }
}
