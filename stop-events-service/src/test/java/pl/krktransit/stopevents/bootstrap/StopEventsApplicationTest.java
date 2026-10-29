package pl.krktransit.stopevents.bootstrap;

import static org.assertj.core.api.Assertions.assertThat;

import com.zaxxer.hikari.HikariDataSource;
import io.grpc.ManagedChannel;
import org.junit.jupiter.api.Test;
import org.springframework.boot.Banner;
import org.springframework.boot.builder.SpringApplicationBuilder;
import pl.krktransit.stopevents.application.port.ScheduleContexts;

class StopEventsApplicationTest {
    @Test
    void realSpringWiringStartsWithoutExternalConnectionsWhenJobsAreDisabled() {
        try (var context = new SpringApplicationBuilder(StopEventsApplication.class)
                .bannerMode(Banner.Mode.OFF).logStartupInfo(false).run(
                        "--stop-events.jobs-enabled=false",
                        "--spring.datasource.url=jdbc:postgresql://127.0.0.1:1/not-used",
                        "--spring.datasource.username=not-used",
                        "--spring.datasource.password=not-used")) {
            assertThat(context.getBean(ScheduleContexts.class)).isNotNull();
            assertThat(context.getBean(ManagedChannel.class).authority()).isEqualTo("127.0.0.1:9090");
            assertThat(context.getBean(HikariDataSource.class).getHikariPoolMXBean()).isNull();
        }
    }
}
