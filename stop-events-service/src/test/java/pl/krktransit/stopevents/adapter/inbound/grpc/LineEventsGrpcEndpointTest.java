package pl.krktransit.stopevents.adapter.inbound.grpc;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.google.protobuf.Timestamp;
import io.grpc.*;
import io.grpc.inprocess.InProcessChannelBuilder;
import io.grpc.inprocess.InProcessServerBuilder;
import io.grpc.stub.MetadataUtils;
import java.time.Instant;
import java.util.List;
import java.util.OptionalLong;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import pl.krktransit.stopevents.application.lineevents.LineEventReader;
import pl.krktransit.stopevents.application.lineevents.LineEvents;
import pl.krktransit.stopevents.application.lineevents.LineEventsFailure;
import pl.krktransit.stopevents.contracts.v1.LineEventsServiceGrpc;
import pl.krktransit.stopevents.contracts.v1.ListLineEventsRequest;
import pl.krktransit.stopevents.domain.lineevents.LineEventsPage;
import pl.krktransit.stopevents.domain.lineevents.LineEventsQuery;

class LineEventsGrpcEndpointTest {
    private static final ListLineEventsRequest REQUEST = ListLineEventsRequest.newBuilder().setCityId("krakow")
            .setLineNumber("139").setCreatedAfter(Timestamp.newBuilder().setSeconds(1_793_000_000L).setNanos(1_500))
            .build();
    private final java.util.concurrent.ExecutorService rpc = Executors.newVirtualThreadPerTaskExecutor();
    private final List<LineEventsQuery> queries = new CopyOnWriteArrayList<>();
    private Server server;
    private ManagedChannel channel;

    @AfterEach
    void close() throws InterruptedException {
        if (channel != null) channel.shutdownNow().awaitTermination(5, TimeUnit.SECONDS);
        if (server != null) server.shutdownNow().awaitTermination(5, TimeUnit.SECONDS);
        rpc.close();
    }

    @Test
    void passesTheCursorAndDefaultsThePageSize() throws Exception {
        start(this::empty);
        var response = client().listLineEvents(REQUEST);
        assertThat(response.getHasMore()).isFalse();
        assertThat(queries).singleElement().satisfies(query -> {
            assertThat(query.cityId()).isEqualTo("krakow");
            assertThat(query.lineNumber()).isEqualTo("139");
            assertThat(query.createdAfter()).isEqualTo(Instant.ofEpochSecond(1_793_000_000L, 1_500));
            assertThat(query.afterId()).isEmpty();
            assertThat(query.pageSize()).isEqualTo(2_000);
        });
        client().listLineEvents(REQUEST.toBuilder().setAfterId(0).setPageSize(5_000).build());
        assertThat(queries.getLast().afterId()).isEqualTo(OptionalLong.of(0));
        assertThat(queries.getLast().pageSize()).isEqualTo(5_000);
    }

    @Test
    void missingAuthenticationAndInvalidRequestsHaveExplicitStatuses() throws Exception {
        start(query -> { throw new AssertionError("Invalid requests must not reach SQL"); });
        assertStatus(Status.Code.UNAUTHENTICATED, () -> LineEventsServiceGrpc.newBlockingStub(channel).listLineEvents(REQUEST));
        assertInvalid(REQUEST.toBuilder().clearCityId().build());
        assertInvalid(REQUEST.toBuilder().clearLineNumber().build());
        assertInvalid(REQUEST.toBuilder().setLineNumber("x' OR 1=1").build());
        assertInvalid(REQUEST.toBuilder().clearCreatedAfter().build());
        assertInvalid(REQUEST.toBuilder().setCreatedAfter(Timestamp.newBuilder().setSeconds(1).setNanos(1_000_000_000)).build());
        assertInvalid(REQUEST.toBuilder().setCreatedAfter(Timestamp.newBuilder().setSeconds(Long.MAX_VALUE)).build());
        assertInvalid(REQUEST.toBuilder().setPageSize(0).build());
        assertInvalid(REQUEST.toBuilder().setPageSize(5_001).build());
        assertInvalid(REQUEST.toBuilder().setAfterId(-1).build());
    }

    @ParameterizedTest
    @EnumSource(LineEventsFailure.Kind.class)
    void mapsExpectedFailures(LineEventsFailure.Kind kind) throws Exception {
        start(query -> { throw new LineEventsFailure(kind, "Expected fixture failure"); });
        var expected = switch (kind) {
            case UNAVAILABLE -> Status.Code.UNAVAILABLE;
            case BUSY -> Status.Code.RESOURCE_EXHAUSTED;
        };
        assertStatus(expected, () -> client().listLineEvents(REQUEST));
    }

    @Test
    void unexpectedFailuresAreInternalWithoutLeakingTheirMessage() throws Exception {
        start(query -> { throw new IllegalStateException("internal detail"); });
        assertThatThrownBy(() -> client().listLineEvents(REQUEST)).isInstanceOfSatisfying(StatusRuntimeException.class, failure -> {
            assertThat(failure.getStatus().getCode()).isEqualTo(Status.Code.INTERNAL);
            assertThat(failure.getStatus().getDescription()).doesNotContain("internal detail");
        });
    }

    private LineEventsPage empty(LineEventsQuery query) {
        queries.add(query);
        return new LineEventsPage(List.of(), false);
    }

    private void start(LineEventReader reader) throws Exception {
        var name = InProcessServerBuilder.generateName();
        server = InProcessServerBuilder.forName(name).executor(rpc).addService(ServerInterceptors.intercept(
                new LineEventsGrpcEndpoint(new LineEvents(reader, 4)), new BearerTokenInterceptor("test-token"))).build().start();
        channel = InProcessChannelBuilder.forName(name).directExecutor().build();
    }

    private LineEventsServiceGrpc.LineEventsServiceBlockingStub client() {
        var headers = new Metadata();
        headers.put(Metadata.Key.of("authorization", Metadata.ASCII_STRING_MARSHALLER), "Bearer test-token");
        return LineEventsServiceGrpc.newBlockingStub(channel).withInterceptors(MetadataUtils.newAttachHeadersInterceptor(headers));
    }

    private void assertInvalid(ListLineEventsRequest request) {
        assertStatus(Status.Code.INVALID_ARGUMENT, () -> client().listLineEvents(request));
    }

    private void assertStatus(Status.Code code, Runnable call) {
        assertThatThrownBy(call::run).isInstanceOfSatisfying(StatusRuntimeException.class,
                failure -> assertThat(failure.getStatus().getCode()).isEqualTo(code));
    }
}
