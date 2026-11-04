package pl.krktransit.livevehicles.bootstrap;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.util.List;
import org.junit.jupiter.api.Test;

class LiveVehiclesPropertiesTest {
    @Test
    void replayCoversTheLongerOfBothRetentions() {
        assertThat(properties(Duration.ofMinutes(1), Duration.ofMinutes(5)).replayWindow()).isEqualTo(Duration.ofMinutes(5));
        assertThat(properties(Duration.ofMinutes(10), Duration.ofMinutes(5)).replayWindow()).isEqualTo(Duration.ofMinutes(10));
    }

    private LiveVehiclesProperties properties(Duration retention, Duration forecastRetention) {
        var redis = new LiveVehiclesProperties.Redis("redis://127.0.0.1:6379", "user", "password", "client",
                Duration.ofSeconds(1), Duration.ofSeconds(5));
        return new LiveVehiclesProperties(true, List.of(new LiveVehiclesProperties.Source("krakow", "mpk-bus")), "prefix", 1,
                Duration.ofSeconds(1), retention, 1, forecastRetention, 1, Duration.ofSeconds(1), Duration.ofSeconds(1), "", redis);
    }
}
