package pl.krktransit.realtime.ingestion.bootstrap;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.IOException;
import java.time.Duration;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.boot.env.YamlPropertySourceLoader;
import org.springframework.core.env.MapPropertySource;
import org.springframework.core.env.PropertySource;
import org.springframework.core.env.StandardEnvironment;
import org.springframework.core.io.ClassPathResource;
import pl.krktransit.realtime.ingestion.domain.source.FeedKind;
import pl.krktransit.realtime.ingestion.domain.source.SourceId;

class RealtimePropertiesTest {
    @Test
    void shippedConfigurationDefinesSixEndpointsAndOneStreamCapPerSource() throws IOException {
        var properties = bind(Map.of());

        assertThat(properties.endpoints()).hasSize(6);
        assertThat(properties.endpoints()).filteredOn(endpoint -> endpoint.kind() == FeedKind.VEHICLE_POSITIONS)
                .extracting(endpoint -> endpoint.url().toString()).containsExactly(
                        "https://gtfs.ztp.krakow.pl/VehiclePositions_A.pb",
                        "https://gtfs.ztp.krakow.pl/VehiclePositions_T.pb",
                        "https://gtfs.ztp.krakow.pl/VehiclePositions_M.pb");
        assertThat(properties.streamMaxLengths()).containsExactly(
                Map.entry(new SourceId("krakow", "mpk-bus"), 300_000L),
                Map.entry(new SourceId("krakow", "mpk-tram"), 300_000L),
                Map.entry(new SourceId("krakow", "mobilis-bus"), 300_000L));
    }

    @Test
    void shippedConfigurationUsesTheAgreedPollingAndPublicationSettings() throws IOException {
        var properties = bind(Map.of());

        assertThat(properties.pollDelay()).isEqualTo(Duration.ofSeconds(4));
        assertThat(properties.failureDelays()).containsExactly(Duration.ofSeconds(4), Duration.ofSeconds(8),
                Duration.ofSeconds(16), Duration.ofSeconds(30), Duration.ofSeconds(60), Duration.ofSeconds(120));
        assertThat(properties.http().downloadTimeout()).isEqualTo(Duration.ofSeconds(30));
        assertThat(properties.http().maxConcurrent()).isEqualTo(3);
        assertThat(properties.http().maxPerHost()).isEqualTo(3);
        var redis = properties.redis();
        assertThat(redis.url()).isEqualTo("redis://redis.test:6379");
        assertThat(redis.streamPrefix()).isEqualTo("krktransit:realtime:observations:v1");
        assertThat(redis.commandTimeout()).isEqualTo(Duration.ofSeconds(10));
        assertThat(redis.username()).isEmpty();
        assertThat(redis.password()).isEmpty();
    }

    @Test
    void rejectsDuplicateSources() throws IOException {
        var shipped = bind(Map.of());
        var duplicated = List.of(shipped.sources().getFirst(), shipped.sources().getFirst());

        assertThatThrownBy(() -> new RealtimeProperties(duplicated, true, shipped.pollDelay(), shipped.failureDelays(),
                shipped.failureJitter(), shipped.shutdownTimeout(), shipped.reportInterval(), shipped.http(),
                shipped.redis())).isInstanceOf(IllegalArgumentException.class).hasMessageContaining("unique");
    }

    private static RealtimeProperties bind(Map<String, Object> overrides) throws IOException {
        var environment = new StandardEnvironment();
        environment.getPropertySources().addFirst(new MapPropertySource("test", withBootstrap(overrides)));
        List<PropertySource<?>> yaml = new YamlPropertySourceLoader().load("application",
                new ClassPathResource("application.yml"));
        yaml.forEach(environment.getPropertySources()::addLast);
        return Binder.get(environment).bind("realtime", RealtimeProperties.class).get();
    }

    private static Map<String, Object> withBootstrap(Map<String, Object> overrides) {
        var values = new HashMap<>(overrides);
        values.put("REALTIME_REDIS_URL", "redis://redis.test:6379");
        return values;
    }
}
