package pl.krktransit.schedule.bootstrap;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.context.ConfigDataApplicationContextInitializer;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Configuration;

class SchedulePropertiesTest {
    private final ApplicationContextRunner context = new ApplicationContextRunner()
            .withInitializer(new ConfigDataApplicationContextInitializer())
            .withUserConfiguration(PropertiesOnly.class);

    @Test
    void bindsInitialFeedsAndDownloadDefaults() {
        context.run(application -> {
            assertThat(application).hasNotFailed();
            var properties = application.getBean(ScheduleProperties.class);
            assertThat(properties.feedConfigs()).extracting(feed -> feed.id().toString())
                    .containsExactly("krakow:mpk-bus", "krakow:mobilis-bus", "krakow:mpk-tram");
            assertThat(properties.download().maxConcurrent()).isEqualTo(3);
            assertThat(properties.grpcMaxConcurrentCalls()).isEqualTo(2500);
            assertThat(application.getEnvironment().getProperty("spring.flyway.enabled")).isEqualTo("false");
        });
    }

    @Test
    void configurationCanReplaceTheSourcesWithoutChangingCode() {
        context.withPropertyValues("schedule.feeds[0].city-id=warsaw", "schedule.feeds[0].source-id=ztm",
                "schedule.feeds[0].static-url=http://127.0.0.1/feed.zip", "schedule.jobs-enabled=false",
                "schedule.grpc-max-concurrent-calls=7")
                .run(application -> {
                    assertThat(application).hasNotFailed();
                    var properties = application.getBean(ScheduleProperties.class);
                    assertThat(properties.feedConfigs()).hasSize(1);
                    assertThat(properties.feedConfigs().getFirst().id().toString()).isEqualTo("warsaw:ztm");
                    assertThat(properties.jobsEnabled()).isFalse();
                    assertThat(properties.grpcMaxConcurrentCalls()).isEqualTo(7);
                });
    }

    @ParameterizedTest
    @ValueSource(ints = {0, -1})
    void refusesNonpositiveGrpcConcurrencyLimits(int maximum) {
        context.withPropertyValues("schedule.grpc-max-concurrent-calls=" + maximum).run(application -> {
            assertThat(application).hasFailed();
            assertThat(application.getStartupFailure()).hasRootCauseInstanceOf(IllegalArgumentException.class);
        });
    }

    @Test
    void keepsGrpcTokenUsableWithoutIncludingItInDiagnosticOutput() {
        String token = "disposable-sensitive-token";
        context.withPropertyValues("schedule.grpc-token=" + token).run(application -> {
            var properties = application.getBean(ScheduleProperties.class);
            assertThat(properties.grpcToken()).isEqualTo(token);
            assertThat(properties.toString()).contains("grpcToken=[REDACTED]").doesNotContain(token);
        });
    }

    @Test
    void refusesAnExplicitMigrationWithoutAnExplicitDatabaseTarget() {
        assertThatThrownBy(() -> MigrationCommand.run(Map.of()))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("SCHEDULE_DB_URL");
    }

    @Configuration(proxyBeanMethods = false)
    @EnableConfigurationProperties(ScheduleProperties.class)
    static class PropertiesOnly {}
}
