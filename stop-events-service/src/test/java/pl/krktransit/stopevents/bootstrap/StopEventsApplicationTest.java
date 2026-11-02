package pl.krktransit.stopevents.bootstrap;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.google.protobuf.Timestamp;
import com.zaxxer.hikari.HikariDataSource;
import io.grpc.ManagedChannel;
import io.grpc.Metadata;
import io.grpc.Status;
import io.grpc.StatusRuntimeException;
import io.grpc.netty.NettyChannelBuilder;
import io.grpc.stub.MetadataUtils;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.springframework.boot.Banner;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.context.ApplicationListener;
import org.springframework.grpc.server.lifecycle.GrpcServerStartedEvent;
import pl.krktransit.stopevents.application.port.ScheduleContexts;
import pl.krktransit.stopevents.contracts.v1.LineEventsServiceGrpc;
import pl.krktransit.stopevents.contracts.v1.ListLineEventsRequest;

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
            assertThat(context.getBean(StatisticsProperties.class).maximumCalculations()).isEqualTo(100);
        }
    }

    @Test
    void realServerExposesLineEventsBehindTheTokenAndReportsStorageOutagesAsUnavailable() throws Exception {
        var port = new CompletableFuture<Integer>();
        try (var context = new SpringApplicationBuilder(StopEventsApplication.class)
                .bannerMode(Banner.Mode.OFF).logStartupInfo(false)
                .listeners((ApplicationListener<GrpcServerStartedEvent>) event -> port.complete(event.getPort())).run(
                        "--stop-events.jobs-enabled=false",
                        "--spring.grpc.server.port=0",
                        "--stop-events.statistics.grpc-token=test-token",
                        "--stop-events.statistics.connection-wait=500ms",
                        "--spring.datasource.url=jdbc:postgresql://127.0.0.1:1/not-used",
                        "--spring.datasource.username=not-used",
                        "--spring.datasource.password=not-used")) {
            var channel = NettyChannelBuilder.forAddress("127.0.0.1", port.get(5, TimeUnit.SECONDS)).usePlaintext().build();
            try {
                var request = ListLineEventsRequest.newBuilder().setCityId("krakow").setLineNumber("139")
                        .setCreatedAfter(Timestamp.newBuilder().setSeconds(1_793_000_000L)).build();
                var anonymous = LineEventsServiceGrpc.newBlockingStub(channel).withDeadlineAfter(10, TimeUnit.SECONDS);
                assertStatus(Status.Code.UNAUTHENTICATED, () -> anonymous.listLineEvents(request));
                var headers = new Metadata();
                headers.put(Metadata.Key.of("authorization", Metadata.ASCII_STRING_MARSHALLER), "Bearer test-token");
                var client = anonymous.withInterceptors(MetadataUtils.newAttachHeadersInterceptor(headers));
                assertStatus(Status.Code.INVALID_ARGUMENT, () -> client.listLineEvents(request.toBuilder().setPageSize(5_001).build()));
                assertStatus(Status.Code.UNAVAILABLE, () -> client.listLineEvents(request));
            } finally { channel.shutdownNow().awaitTermination(5, TimeUnit.SECONDS); }
        }
    }

    private static void assertStatus(Status.Code code, Runnable call) {
        assertThatThrownBy(call::run).isInstanceOfSatisfying(StatusRuntimeException.class,
                failure -> assertThat(failure.getStatus().getCode()).isEqualTo(code));
    }
}
