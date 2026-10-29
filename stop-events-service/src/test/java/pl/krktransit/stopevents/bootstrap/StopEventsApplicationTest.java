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
                        "--spring.grpc.server.port=0",
                        "--spring.datasource.url=jdbc:postgresql://127.0.0.1:1/not-used",
                        "--spring.datasource.username=not-used",
                        "--spring.datasource.password=not-used")) {
            assertThat(context.getBean(ScheduleContexts.class)).isNotNull();
            assertThat(context.getBean(ManagedChannel.class).authority()).isEqualTo("127.0.0.1:9090");
            var writer = context.getBean("dataSource", HikariDataSource.class);
            var reader = context.getBean("statisticsDataSource", HikariDataSource.class);
            assertThat(writer.getHikariPoolMXBean()).isNull();
            assertThat(reader.getHikariPoolMXBean()).isNull();
            assertThat(writer.getMaximumPoolSize()).isEqualTo(4);
            assertThat(reader.getMaximumPoolSize()).isEqualTo(8);
            assertThat(reader.isReadOnly()).isTrue();
        }
    }
}
