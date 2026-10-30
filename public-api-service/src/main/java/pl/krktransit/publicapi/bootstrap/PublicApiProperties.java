package pl.krktransit.publicapi.bootstrap;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;
import pl.krktransit.publicapi.adapter.outbound.grpc.GrpcCallSettings;

@ConfigurationProperties("public-api")
public record PublicApiProperties(Statistics statistics) {
    public PublicApiProperties {
        requirePresent(statistics);
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
}
