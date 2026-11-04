package pl.krktransit.stopevents.adapter.inbound.grpc;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.grpc.*;
import io.grpc.inprocess.InProcessChannelBuilder;
import io.grpc.inprocess.InProcessServerBuilder;
import io.grpc.stub.MetadataUtils;
import java.io.IOException;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.*;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import pl.krktransit.stopevents.application.statistics.*;
import pl.krktransit.stopevents.contracts.v1.*;
import pl.krktransit.stopevents.domain.statistics.*;

class StatisticsGrpcEndpointTest {
    private static final StatisticsRequest REQUEST = StatisticsRequest.newBuilder().setLineNumber("139")
            .setStartDate("2026-03-19").setEndDate("2026-03-19").build();
    private static final Clock CLOCK = Clock.fixed(Instant.parse("2026-10-29T00:00:00Z"), ZoneOffset.UTC);
    private final java.util.concurrent.ExecutorService rpc = Executors.newVirtualThreadPerTaskExecutor();
    private StatisticsQueries queries;
    private Server server;
    private ManagedChannel channel;

    @AfterEach
    void close() throws InterruptedException {
        if (channel != null) channel.shutdownNow().awaitTermination(5, TimeUnit.SECONDS);
        if (server != null) server.shutdownNow().awaitTermination(5, TimeUnit.SECONDS);
        if (queries != null) queries.close();
        rpc.close();
    }

    @Test
    void allFiveMethodsReturnTypedResponsesWithTheOriginalQuery() throws IOException {
        start((kind, query, cancellation) -> switch (kind) {
            case DELAY_MAP -> new StatisticsResult.DelayMap(List.of());
            case MAX_DELAY -> new StatisticsResult.MaxDelay(0, List.of());
            case ROUTE_DELAY -> new StatisticsResult.RouteDelay(0, List.of());
            case PUNCTUALITY -> new StatisticsResult.Punctuality(4, 2, 1, 1);
            case TREND -> new StatisticsResult.Trend(List.of(new StatisticsResult.Day(LocalDate.of(2026, 3, 19), 42.5, 3)));
        });
        var client = client();
        assertThat(client.getMaxDelay(REQUEST).getTripsAnalyzed()).isZero();
        assertThat(client.getRouteDelay(REQUEST).getQuery()).isEqualTo(REQUEST);
        assertThat(client.getPunctuality(REQUEST).getOnTimePercent()).isEqualTo(50);
        var trend = client.getTrend(REQUEST);
        assertThat(trend.getDays(0).getAvgDelaySeconds()).isEqualTo(42.5);
        assertThat(trend.getQuery()).isEqualTo(REQUEST);
        assertThat(client.getDelayMap(REQUEST).getQuery()).isEqualTo(REQUEST);
        assertThat(client.getDelayMap(REQUEST).getSegmentsList()).isEmpty();
    }

    @Test
    void missingAuthenticationAndInvalidRequestsHaveExplicitStatuses() throws Exception {
        start((kind, query, cancellation) -> { throw new AssertionError("Invalid requests must not reach SQL"); });
        assertStatus(Status.Code.UNAUTHENTICATED, () -> StatisticsServiceGrpc.newBlockingStub(channel).getTrend(REQUEST));
        assertStatus(Status.Code.INVALID_ARGUMENT, () -> client().getTrend(REQUEST.toBuilder().clearStartDate().build()));
        assertStatus(Status.Code.INVALID_ARGUMENT, () -> client().getTrend(REQUEST.toBuilder().setLineNumber("x' OR 1=1").build()));
        assertStatus(Status.Code.INVALID_ARGUMENT, () -> client().getTrend(REQUEST.toBuilder().setEndDate("2026-10-30").build()));
    }

    @ParameterizedTest
    @EnumSource(StatisticsFailure.Kind.class)
    void mapsExpectedFailuresWithoutConfusingNoDataAndOutages(StatisticsFailure.Kind kind) throws Exception {
        start((statistic, query, cancellation) -> { throw new StatisticsFailure(kind, "Expected fixture failure"); });
        var expected = switch (kind) {
            case NOT_FOUND -> Status.Code.NOT_FOUND;
            case UNAVAILABLE -> Status.Code.UNAVAILABLE;
            case BUSY -> Status.Code.RESOURCE_EXHAUSTED;
            case CANCELLED -> Status.Code.CANCELLED;
        };
        assertStatus(expected, () -> client().getTrend(REQUEST));
    }

    @Test
    void unexpectedFailuresAreInternalWithoutLeakingTheirMessage() throws Exception {
        start((kind, query, cancellation) -> {
            if (kind == Statistic.TREND) throw new IllegalArgumentException("internal detail");
            throw new NullPointerException("internal detail");
        });
        for (Runnable call : List.<Runnable>of(() -> client().getTrend(REQUEST), () -> client().getPunctuality(REQUEST))) {
            assertThatThrownBy(call::run).isInstanceOfSatisfying(StatusRuntimeException.class, failure -> {
                assertThat(failure.getStatus().getCode()).isEqualTo(Status.Code.INTERNAL);
                assertThat(failure.getStatus().getDescription()).doesNotContain("internal detail");
            });
        }
    }

    @Test
    void deadlineCancelsTheQueryInsteadOfOnlyAbandoningTheResponse() throws Exception {
        var entered = new CountDownLatch(1);
        var cancelled = new CountDownLatch(1);
        start((kind, query, cancellation) -> {
            try (var registration = cancellation.onCancel(cancelled::countDown)) {
                entered.countDown();
                try { cancelled.await(); }
                catch (InterruptedException interrupted) { Thread.currentThread().interrupt(); }
                cancellation.check();
                return new StatisticsResult.Trend(List.of());
            }
        });
        var call = java.util.concurrent.CompletableFuture.runAsync(() ->
                assertStatus(Status.Code.DEADLINE_EXCEEDED, () -> client().withDeadlineAfter(1, TimeUnit.SECONDS).getTrend(REQUEST)), rpc);
        assertThat(entered.await(5, TimeUnit.SECONDS)).isTrue();
        call.get(5, TimeUnit.SECONDS);
        assertThat(cancelled.await(5, TimeUnit.SECONDS)).isTrue();
    }

    private void start(StatisticsReader reader) throws IOException {
        StatisticsCache cache = new StatisticsCache() {
            @Override public Optional<StatisticsResult> get(Statistic kind, StatisticsQuery query) { return Optional.empty(); }
            @Override public void put(Statistic kind, StatisticsQuery query, StatisticsResult result) {}
        };
        queries = new StatisticsQueries(reader, cache, CLOCK, Executors.newVirtualThreadPerTaskExecutor(), 10);
        var name = InProcessServerBuilder.generateName();
        server = InProcessServerBuilder.forName(name).executor(rpc).addService(ServerInterceptors.intercept(
                new StatisticsGrpcEndpoint(queries), new BearerTokenInterceptor("test-token"))).build().start();
        channel = InProcessChannelBuilder.forName(name).directExecutor().build();
    }

    private StatisticsServiceGrpc.StatisticsServiceBlockingStub client() {
        var headers = new Metadata();
        headers.put(Metadata.Key.of("authorization", Metadata.ASCII_STRING_MARSHALLER), "Bearer test-token");
        return StatisticsServiceGrpc.newBlockingStub(channel).withInterceptors(MetadataUtils.newAttachHeadersInterceptor(headers));
    }

    private void assertStatus(Status.Code code, Runnable call) {
        assertThatThrownBy(call::run).isInstanceOfSatisfying(StatusRuntimeException.class,
                failure -> assertThat(failure.getStatus().getCode()).isEqualTo(code));
    }
}
