package pl.krktransit.stopevents.adapter.inbound.stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static pl.krktransit.stopevents.support.DetectorFixtures.*;

import com.google.protobuf.InvalidProtocolBufferException;
import com.google.protobuf.Timestamp;
import org.junit.jupiter.api.Test;
import pl.krktransit.realtime.contracts.v1.*;
import pl.krktransit.stopevents.domain.observation.*;

class ObservationWireMapperTest {
    private final ObservationWireMapper mapper = new ObservationWireMapper();

    @Test
    void missingStatusStaysAbsentAndZeroSequenceIsPreserved() throws InvalidProtocolBufferException {
        var wire = origin().setVehiclePosition(VehiclePosition.newBuilder().setTripId(" 001:x ")
                .setLicensePlate("KR123").setCurrentStopSequence(0).setTimestamp(BASE.getEpochSecond())).build();
        var vehicle = (VehicleObservation) mapper.decode(SOURCE, wire.toByteArray());
        assertThat(vehicle.tripId()).isEqualTo(SOURCE.scope(" 001:x "));
        assertThat(vehicle.sequence()).isZero();
        assertThat(vehicle.status()).isNull();
    }

    @Test
    void triplessVpIsDecodedButIneligibleForDetection() throws InvalidProtocolBufferException {
        var wire = origin().setVehiclePosition(VehiclePosition.newBuilder().setLicensePlate("KR123")
                .setCurrentStopSequence(1).setTimestamp(BASE.getEpochSecond())).build();
        assertThat(((VehicleObservation) mapper.decode(SOURCE, wire.toByteArray())).eligible()).isFalse();
    }

    @Test
    void absoluteTimesArePreservedAndDelayOnlyIsNotEligible() throws InvalidProtocolBufferException {
        var wire = origin().setTripUpdate(TripUpdate.newBuilder().setTripId("trip")
                .addStopTimeUpdates(StopTimeUpdate.newBuilder().setStopId("A").setStopSequence(0)
                        .setArrival(StopTimeEvent.newBuilder().setTime(BASE.getEpochSecond())))
                .addStopTimeUpdates(StopTimeUpdate.newBuilder().setStopId("B")
                        .setArrival(StopTimeEvent.newBuilder().setDelay(10)))).build();
        var update = (ForecastUpdate) mapper.decode(SOURCE, wire.toByteArray());
        assertThat(update.stops().getFirst().sequence()).isZero();
        assertThat(update.stops().getFirst().usable()).isTrue();
        assertThat(update.stops().getLast().usable()).isFalse();
    }

    @Test
    void malformedOriginAndTimestampAreRejectedBeforeTheyReachState() {
        var wrongSource = origin().setSource(Source.newBuilder().setCityId("krakow").setSourceId("mpk-tram"))
                .setTripUpdate(TripUpdate.newBuilder().setTripId("trip")).build();
        assertThatThrownBy(() -> mapper.decode(SOURCE, wrongSource.toByteArray())).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> mapper.decode(SOURCE, new byte[]{-1})).isInstanceOf(InvalidProtocolBufferException.class);
    }

    private pl.krktransit.realtime.contracts.v1.Observation.Builder origin() {
        return pl.krktransit.realtime.contracts.v1.Observation.newBuilder()
                .setSource(Source.newBuilder().setCityId(SOURCE.cityId()).setSourceId(SOURCE.sourceId()))
                .setFetchedAt(Timestamp.newBuilder().setSeconds(BASE.getEpochSecond()));
    }
}
