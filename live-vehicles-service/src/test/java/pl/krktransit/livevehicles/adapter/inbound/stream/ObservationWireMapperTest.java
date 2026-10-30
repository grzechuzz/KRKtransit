package pl.krktransit.livevehicles.adapter.inbound.stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.google.protobuf.Timestamp;
import java.time.Instant;
import org.junit.jupiter.api.Test;
import pl.krktransit.livevehicles.domain.SourceId;
import pl.krktransit.livevehicles.domain.StopProgress;
import pl.krktransit.livevehicles.domain.VehicleStopStatus;
import pl.krktransit.realtime.contracts.v1.Observation;
import pl.krktransit.realtime.contracts.v1.Position;
import pl.krktransit.realtime.contracts.v1.Source;
import pl.krktransit.realtime.contracts.v1.TripUpdate;
import pl.krktransit.realtime.contracts.v1.VehiclePosition;

class ObservationWireMapperTest {
    private static final SourceId SOURCE = new SourceId("krakow", "mpk-bus");
    private final ObservationWireMapper mapper = new ObservationWireMapper();

    @Test
    void coordinatesNeedNoStopStatusOrSequenceAndPreserveZerosAndOriginalTripBytes() throws Exception {
        var observation = envelope().setVehiclePosition(vehicle().setTripId(" 000:trip ")).build();
        assertThat(mapper.decode(SOURCE, "123-1", observation.toByteArray())).get().satisfies(position -> {
            assertThat(position.tripId()).isEqualTo("krakow:mpk-bus: 000:trip ");
            assertThat(position.latitude()).isZero();
            assertThat(position.longitude()).isZero();
            assertThat(position.bearing()).isNull();
            assertThat(position.timestamp()).isEqualTo(Instant.ofEpochSecond(100));
        });
    }

    @Test
    void vehiclesWithoutTripTimestampOrValidBearingAreKept() throws Exception {
        var observation = envelope().setVehiclePosition(vehicle().clearTripId().clearTimestamp()
                .setPosition(Position.newBuilder().setLatitude(50).setLongitude(20).setBearing(-1))).build();
        assertThat(mapper.decode(SOURCE, "123-1", observation.toByteArray())).get().satisfies(position -> {
            assertThat(position.tripId()).isNull();
            assertThat(position.timestamp()).isNull();
            assertThat(position.bearing()).isNull();
            assertThat(position.observedAt()).isEqualTo(Instant.ofEpochSecond(100));
        });
        assertThat(mapper.decode(SOURCE, "123-1", envelope().setVehiclePosition(vehicle()
                .setTimestamp(31556889864403199L)).build().toByteArray())).get()
                .satisfies(position -> assertThat(position.timestamp()).isNull());
    }

    @Test
    void stopProgressIsCopiedWithScopedStopIdAndZeroValuesPreserved() throws Exception {
        var observation = envelope().setVehiclePosition(vehicle().setStopId(" 0042 ").setCurrentStopSequence(0)
                .setCurrentStatus(0)).build();
        assertThat(mapper.decode(SOURCE, "123-1", observation.toByteArray())).get().satisfies(position ->
                assertThat(position.progress()).isEqualTo(
                        new StopProgress("krakow:mpk-bus: 0042 ", 0, VehicleStopStatus.INCOMING_AT)));
    }

    @Test
    void absentProgressStaysAbsentAndBlankStopIdsAreDropped() throws Exception {
        assertThat(mapper.decode(SOURCE, "123-1", envelope().setVehiclePosition(vehicle()).build().toByteArray())).get()
                .satisfies(position -> assertThat(position.progress()).isEqualTo(StopProgress.NONE));
        assertThat(mapper.decode(SOURCE, "123-1", envelope().setVehiclePosition(vehicle().setStopId(" ")
                .setCurrentStopSequence(12)).build().toByteArray())).get().satisfies(position ->
                assertThat(position.progress()).isEqualTo(new StopProgress(null, 12, null)));
    }

    @Test
    void knownStatusCodesAreMappedAndOtherPresentCodesAreUnrecognized() throws Exception {
        assertThat(status(1)).isEqualTo(VehicleStopStatus.STOPPED_AT);
        assertThat(status(2)).isEqualTo(VehicleStopStatus.IN_TRANSIT_TO);
        assertThat(status(7)).isEqualTo(VehicleStopStatus.UNRECOGNIZED);
        assertThat(status(-1)).isEqualTo(VehicleStopStatus.UNRECOGNIZED);
    }

    @Test
    void tripUpdatesAndPositionsWithoutCoordinatesOrIdentityAreIgnored() throws Exception {
        assertThat(mapper.decode(SOURCE, "123-1", envelope().setTripUpdate(TripUpdate.newBuilder()).build().toByteArray())).isEmpty();
        assertThat(mapper.decode(SOURCE, "123-1", envelope().setVehiclePosition(vehicle().clearPosition()).build().toByteArray())).isEmpty();
        assertThat(mapper.decode(SOURCE, "123-1", envelope().setVehiclePosition(vehicle().clearLicensePlate()).build().toByteArray())).isEmpty();
    }

    @Test
    void invalidCoordinatesAndMismatchedSourcesAreRejected() {
        assertThatThrownBy(() -> mapper.decode(SOURCE, "123-1", envelope().setVehiclePosition(vehicle()
                .setPosition(Position.newBuilder().setLatitude(Float.NaN).setLongitude(0))).build().toByteArray()))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> mapper.decode(new SourceId("krakow", "mpk-tram"), "123-1",
                envelope().setVehiclePosition(vehicle()).build().toByteArray())).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void malformedFetchTimesCannotReachTheProjector() {
        assertThatThrownBy(() -> mapper.decode(SOURCE, "123-1", envelope().setVehiclePosition(vehicle())
                .setFetchedAt(Timestamp.newBuilder().setSeconds(100).setNanos(-1)).build().toByteArray()))
                .isInstanceOf(IllegalArgumentException.class);
    }

    private VehicleStopStatus status(int code) throws Exception {
        var observation = envelope().setVehiclePosition(vehicle().setCurrentStatus(code)).build();
        return mapper.decode(SOURCE, "123-1", observation.toByteArray()).orElseThrow().progress().status();
    }

    private Observation.Builder envelope() {
        return Observation.newBuilder().setSource(Source.newBuilder().setCityId("krakow").setSourceId("mpk-bus"))
                .setFetchedAt(Timestamp.newBuilder().setSeconds(100));
    }

    private VehiclePosition.Builder vehicle() {
        return VehiclePosition.newBuilder().setTripId("trip").setLicensePlate("A").setTimestamp(100)
                .setPosition(Position.newBuilder().setLatitude(0).setLongitude(0));
    }
}
