package pl.krktransit.livevehicles.bootstrap;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.net.InetAddress;
import java.time.Duration;
import org.junit.jupiter.api.Test;
import org.springframework.boot.Banner;
import org.springframework.boot.builder.SpringApplicationBuilder;
import pl.krktransit.livevehicles.application.LiveVehicleQueries;

class LiveVehiclesApplicationTest {
    @Test
    void springWiringStartsWithoutRedisWhenJobsAndServerAreDisabled() {
        try (var context = new SpringApplicationBuilder(LiveVehiclesApplication.class).bannerMode(Banner.Mode.OFF)
                .logStartupInfo(false).run("--live-vehicles.jobs-enabled=false", "--spring.grpc.server.enabled=false")) {
            assertThat(context.getBean(LiveVehicleQueries.class).list().vehicles()).isEmpty();
            var settings = context.getBean(LiveVehiclesProperties.class);
            assertThat(settings.sourceIds()).hasSize(3);
            assertThat(settings.retention()).isEqualTo(Duration.ofMinutes(10));
            assertThatThrownBy(() -> new LiveVehiclesConfiguration().liveVehiclesAuthentication(settings,
                    InetAddress.getByAddress(new byte[]{0, 0, 0, 0}))).isInstanceOf(IllegalArgumentException.class);
        }
    }
}
