package pl.krktransit.schedule.adapter.inbound.grpc;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertThrows;

import io.grpc.ManagedChannel;
import io.grpc.Metadata;
import io.grpc.Server;
import io.grpc.Status;
import io.grpc.StatusRuntimeException;
import io.grpc.inprocess.InProcessChannelBuilder;
import io.grpc.inprocess.InProcessServerBuilder;
import java.io.IOException;
import java.util.List;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.ValueSource;
import pl.krktransit.schedule.application.importing.ImportFailure;
import pl.krktransit.schedule.application.port.ScheduleReader;
import pl.krktransit.schedule.application.querying.GeometryPoint;
import pl.krktransit.schedule.application.querying.LookupFailure;
import pl.krktransit.schedule.application.querying.ScheduleQueries;
import pl.krktransit.schedule.application.querying.ShapeContext;
import pl.krktransit.schedule.application.querying.TripContext;
import pl.krktransit.schedule.application.querying.TripStop;
import pl.krktransit.schedule.contracts.v1.FeedId;
import pl.krktransit.schedule.contracts.v1.GetCurrentHashRequest;
import pl.krktransit.schedule.contracts.v1.GetShapeRequest;
import pl.krktransit.schedule.contracts.v1.GetTripContextRequest;
import pl.krktransit.schedule.contracts.v1.ScheduleServiceGrpc;
import pl.krktransit.schedule.domain.schedule.ScheduleHash;

class ScheduleGrpcEndpointTest {
    private static final String HASH = "a".repeat(64);
    private static final String TRIP = "krakow:mpk:123";
    private final StubReader reader = new StubReader();
    private Server server;
    private ManagedChannel channel;
    private ScheduleServiceGrpc.ScheduleServiceBlockingStub client;

    @BeforeEach
    void startServer() throws IOException {
        String name = InProcessServerBuilder.generateName();
        server = InProcessServerBuilder.forName(name).directExecutor()
                .addService(new ScheduleGrpcEndpoint(new ScheduleQueries(reader))).build().start();
        channel = InProcessChannelBuilder.forName(name).directExecutor().build();
        client = ScheduleServiceGrpc.newBlockingStub(channel).withDeadlineAfter(5, TimeUnit.SECONDS);
    }

    @AfterEach
    void stopServer() throws InterruptedException {
        channel.shutdownNow().awaitTermination(5, TimeUnit.SECONDS);
        server.shutdownNow().awaitTermination(5, TimeUnit.SECONDS);
    }

    @Test
    void readsCurrentHashForStructuredFeedIdentity() {
        var result = client.getCurrentHash(GetCurrentHashRequest.newBuilder()
                .setFeedId(FeedId.newBuilder().setCityId("krakow").setSourceId("mpk")).build());

        assertThat(result.getHash()).isEqualTo(HASH);
        assertThat(reader.feed).isEqualTo(new pl.krktransit.schedule.domain.feed.FeedId("krakow", "mpk"));
    }

    @Test
    void preservesZeroAndMissingOptionalTripValues() {
        var result = client.getTripContext(tripRequest());

        assertThat(result.getHash()).isEqualTo(HASH);
        assertThat(result.hasDirectionId()).isTrue();
        assertThat(result.getDirectionId()).isZero();
        assertThat(result.hasHeadsign()).isFalse();
        assertThat(result.hasShapeId()).isFalse();
        assertThat(result.getStopsList()).hasSize(2);
        assertThat(result.getStops(0).hasArrivalSeconds()).isTrue();
        assertThat(result.getStops(0).getArrivalSeconds()).isZero();
        assertThat(result.getStops(0).hasDepartureSeconds()).isTrue();
        assertThat(result.getStops(0).getDepartureSeconds()).isZero();
        assertThat(result.getStops(0).hasLatitude()).isTrue();
        assertThat(result.getStops(0).getLatitude()).isZero();
        assertThat(result.getStops(0).hasStopDesc()).isFalse();
        assertThat(result.getStops(1).getArrivalSeconds()).isEqualTo(90_000);
        assertThat(result.getStops(1).getDepartureSeconds()).isEqualTo(90_030);
        assertThat(result.getStops(1).getStopId()).isEqualTo(result.getStops(0).getStopId());
    }

    @Test
    void mapsOrderedShapePointsIncludingDuplicateSequences() {
        var result = client.getShape(GetShapeRequest.newBuilder().setHash(HASH)
                .setShapeId("krakow:mpk:shape").build());

        assertThat(result.getHash()).isEqualTo(HASH);
        assertThat(result.getPointsList()).hasSize(2);
        assertThat(result.getPoints(0).hasLatitude()).isTrue();
        assertThat(result.getPoints(0).getLatitude()).isZero();
        assertThat(result.getPoints(1).getSequence()).isEqualTo(result.getPoints(0).getSequence());
    }

    @Test
    void rejectsMissingFeedAndInvalidHash() {
        assertStatus(Status.Code.INVALID_ARGUMENT,
                () -> client.getCurrentHash(GetCurrentHashRequest.getDefaultInstance()));
        assertStatus(Status.Code.INVALID_ARGUMENT,
                () -> client.getTripContext(GetTripContextRequest.newBuilder().setHash("broken")
                        .setTripId(TRIP).build()));
    }

    @ParameterizedTest
    @EnumSource(LookupFailure.Kind.class)
    void givesStableLookupFailureCodes(LookupFailure.Kind kind) {
        reader.lookupFailure = kind;

        var failure = assertThrows(StatusRuntimeException.class, () -> client.getTripContext(tripRequest()));

        Status.Code expected = switch (kind) {
            case UNKNOWN_FEED, ENTITY_NOT_FOUND -> Status.Code.NOT_FOUND;
            case NOT_PUBLISHED, VERSION_UNAVAILABLE -> Status.Code.FAILED_PRECONDITION;
        };
        assertThat(failure.getStatus().getCode()).isEqualTo(expected);
        assertThat(failure.getTrailers().get(Metadata.Key.of("schedule-error-code", Metadata.ASCII_STRING_MARSHALLER)))
                .isEqualTo(kind.name());
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void rejectsNulBeforeCallingStorage(boolean shape) {
        String id = "krakow:mpk:bad\0id";
        if (shape) {
            assertStatus(Status.Code.INVALID_ARGUMENT, () -> client.getShape(
                    GetShapeRequest.newBuilder().setHash(HASH).setShapeId(id).build()));
        } else {
            assertStatus(Status.Code.INVALID_ARGUMENT, () -> client.getTripContext(
                    GetTripContextRequest.newBuilder().setHash(HASH).setTripId(id).build()));
        }
        assertThat(reader.entityReads).isZero();
    }

    @ParameterizedTest
    @ValueSource(strings = {"  krakow:mpk:001:02  ", "krakow:mpk:żółć"})
    void preservesNonblankIdentifiersWithoutTrimmingOrRestrictingThem(String id) {
        assertThat(client.getTripContext(GetTripContextRequest.newBuilder().setHash(HASH).setTripId(id).build())
                .getTripId()).isEqualTo(id);
        assertThat(client.getShape(GetShapeRequest.newBuilder().setHash(HASH).setShapeId(id).build())
                .getShapeId()).isEqualTo(id);
    }

    @ParameterizedTest
    @EnumSource(value = ImportFailure.Kind.class, names = {"PERSISTENCE", "VALIDATION"})
    void reportsStorageFailureWithoutExposingItsDetails(ImportFailure.Kind kind) {
        reader.storageFailure = kind;

        var failure = assertThrows(StatusRuntimeException.class, () -> client.getTripContext(tripRequest()));

        assertThat(failure.getStatus().getCode()).isEqualTo(
                kind == ImportFailure.Kind.PERSISTENCE ? Status.Code.UNAVAILABLE : Status.Code.INTERNAL);
        assertThat(failure.getTrailers().get(Metadata.Key.of("schedule-error-code", Metadata.ASCII_STRING_MARSHALLER)))
                .isEqualTo(kind == ImportFailure.Kind.PERSISTENCE ? "STORAGE_UNAVAILABLE" : "STORAGE_READ_FAILED");
        assertThat(failure.getStatus().getDescription()).doesNotContain("password");
    }

    private GetTripContextRequest tripRequest() {
        return GetTripContextRequest.newBuilder().setHash(HASH).setTripId(TRIP).build();
    }

    private void assertStatus(Status.Code expected, Runnable call) {
        assertThat(assertThrows(StatusRuntimeException.class, call::run).getStatus().getCode()).isEqualTo(expected);
    }

    private static final class StubReader implements ScheduleReader {
        private pl.krktransit.schedule.domain.feed.FeedId feed;
        private LookupFailure.Kind lookupFailure;
        private ImportFailure.Kind storageFailure;
        private int entityReads;

        @Override
        public ScheduleHash currentHash(pl.krktransit.schedule.domain.feed.FeedId feed) {
            this.feed = feed;
            return new ScheduleHash(HASH);
        }

        @Override
        public TripContext tripContext(ScheduleHash hash, String tripId) {
            entityReads++;
            if (lookupFailure != null) throw new LookupFailure(lookupFailure, "Missing schedule context");
            if (storageFailure != null) throw new ImportFailure(storageFailure, "secret password");
            return new TripContext(hash, tripId, "krakow:mpk:route", "123", 0, null, null, 2,
                    List.of(new TripStop("krakow:mpk:stop", "Start", null, 0.0, null, 1, 0, 0),
                            new TripStop("krakow:mpk:stop", "Start", "Again", null, null, 2, 90_000, 90_030)));
        }

        @Override
        public ShapeContext shape(ScheduleHash hash, String shapeId) {
            entityReads++;
            return new ShapeContext(hash, shapeId, List.of(new GeometryPoint(0, 0, 1), new GeometryPoint(50, 20, 1)));
        }
    }
}
