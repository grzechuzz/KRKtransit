package pl.krktransit.stopevents.adapter.outbound.schedule;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static pl.krktransit.stopevents.support.DetectorFixtures.*;

import io.grpc.*;
import io.grpc.inprocess.*;
import io.grpc.stub.StreamObserver;
import java.io.IOException;
import java.time.*;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import pl.krktransit.schedule.contracts.v1.*;
import pl.krktransit.stopevents.application.port.*;

class GrpcScheduleContextsTest {
    private final FakeSchedule service = new FakeSchedule();
    private Server server;
    private ManagedChannel channel;
    private GrpcScheduleContexts client;

    @BeforeEach
    void start() throws IOException {
        String name = InProcessServerBuilder.generateName();
        server = InProcessServerBuilder.forName(name).directExecutor().addService(service).build().start();
        channel = InProcessChannelBuilder.forName(name).directExecutor().build();
        client = new GrpcScheduleContexts(channel, "test-token", Duration.ofSeconds(2));
    }

    @AfterEach
    void stop() throws InterruptedException {
        channel.shutdownNow().awaitTermination(5, java.util.concurrent.TimeUnit.SECONDS);
        server.shutdownNow().awaitTermination(5, java.util.concurrent.TimeUnit.SECONDS);
    }

    @Test
    void mapsAbsentOptionalFieldsAndDirectionZero() {
        assertThat(client.currentHash(SOURCE)).isEqualTo(HASH);
        var found = (ScheduleContexts.Found) client.tripContext(HASH, TRIP);
        assertThat(found.context().directionId()).isZero();
        assertThat(found.context().stop(1).description()).isNull();
        assertThat(found.context().stop(1).arrivalSeconds()).isZero();
    }

    @Test
    void mapsBusinessOutcomesFromTrailersRatherThanStatusAlone() {
        service.code = "ENTITY_NOT_FOUND";
        service.status = Status.NOT_FOUND;
        assertThat(client.tripContext(HASH, TRIP)).isInstanceOf(ScheduleContexts.UnknownTrip.class);
        service.code = "VERSION_UNAVAILABLE";
        service.status = Status.FAILED_PRECONDITION;
        assertThat(client.tripContext(HASH, TRIP)).isInstanceOf(ScheduleContexts.VersionUnavailable.class);
        service.code = "NOT_PUBLISHED";
        assertThatThrownBy(() -> client.currentHash(SOURCE)).isInstanceOf(ScheduleFailure.class);
    }

    @Test
    void outageAndDeadlineAreTemporaryButBadCredentialsFailFast() {
        service.status = Status.UNAVAILABLE;
        assertThatThrownBy(() -> client.tripContext(HASH, TRIP)).isInstanceOf(ScheduleFailure.class);
        service.status = Status.INTERNAL;
        assertThatThrownBy(() -> client.tripContext(HASH, TRIP)).isInstanceOf(ScheduleFailure.class);
        service.status = Status.RESOURCE_EXHAUSTED;
        assertThatThrownBy(() -> client.currentHash(SOURCE)).isInstanceOf(ScheduleFailure.class);
        service.status = Status.UNAUTHENTICATED;
        assertThatThrownBy(() -> client.tripContext(HASH, TRIP)).isInstanceOf(IllegalStateException.class);
        service.status = null;
        service.hang = true;
        var shortDeadline = new GrpcScheduleContexts(channel, "test-token", Duration.ofMillis(50));
        assertThatThrownBy(() -> shortDeadline.tripContext(HASH, TRIP)).isInstanceOf(ScheduleFailure.class);
    }

    @Test
    void anIncompleteContextIsReportedForItsTripInsteadOfFailingTheDetector() {
        service.incomplete = true;
        var lookup = client.tripContext(HASH, TRIP);
        assertThat(lookup).isInstanceOf(ScheduleContexts.InvalidContext.class);
        assertThat(((ScheduleContexts.InvalidContext) lookup).reason()).contains("incomplete stop occurrence");
    }

    @Test
    void immutableContextsAreCachedByHashAndTripAndFailuresAreNotCached() {
        var cached = new CachedScheduleContexts(client, 2, Duration.ofSeconds(30), Clock.fixed(BASE, ZoneOffset.UTC));
        cached.tripContext(HASH, TRIP);
        cached.tripContext(HASH, TRIP);
        assertThat(service.calls).hasValue(1);
        service.status = Status.UNAVAILABLE;
        assertThatThrownBy(() -> cached.tripContext("b".repeat(64), TRIP)).isInstanceOf(ScheduleFailure.class);
        service.status = null;
        cached.tripContext("b".repeat(64), TRIP);
        assertThat(service.calls).hasValue(3);
    }

    private static final class FakeSchedule extends ScheduleServiceGrpc.ScheduleServiceImplBase {
        private final AtomicInteger calls = new AtomicInteger();
        private String code = "";
        private Status status;
        private boolean hang;
        private boolean incomplete;

        @Override
        public void getCurrentHash(GetCurrentHashRequest request, StreamObserver<GetCurrentHashResponse> response) {
            if (fail(response)) return;
            response.onNext(GetCurrentHashResponse.newBuilder().setHash(HASH).build());
            response.onCompleted();
        }

        @Override
        public void getTripContext(GetTripContextRequest request, StreamObserver<GetTripContextResponse> response) {
            calls.incrementAndGet();
            if (hang || fail(response)) return;
            var stop = TripStop.newBuilder().setStopId(SOURCE.scope("A")).setStopSequence(1).setArrivalSeconds(0)
                    .setDepartureSeconds(0);
            if (!incomplete) stop.setStopName("A");
            response.onNext(GetTripContextResponse.newBuilder().setHash(request.getHash()).setTripId(request.getTripId())
                    .setLineNumber("111").setDirectionId(0).setHeadsign("End").setMaxStopSequence(1)
                    .addStops(stop).build());
            response.onCompleted();
        }

        private boolean fail(StreamObserver<?> response) {
            if (status == null) return false;
            var metadata = new Metadata();
            metadata.put(Metadata.Key.of("schedule-error-code", Metadata.ASCII_STRING_MARSHALLER), code);
            response.onError(status.asRuntimeException(metadata));
            return true;
        }
    }
}
