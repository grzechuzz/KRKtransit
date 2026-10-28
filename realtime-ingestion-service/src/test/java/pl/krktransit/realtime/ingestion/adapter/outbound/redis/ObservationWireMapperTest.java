package pl.krktransit.realtime.ingestion.adapter.outbound.redis;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import org.junit.jupiter.api.Test;
import pl.krktransit.realtime.contracts.v1.Observation;
import pl.krktransit.realtime.ingestion.domain.observation.ObservationOrigin;
import pl.krktransit.realtime.ingestion.domain.observation.StopEvent;
import pl.krktransit.realtime.ingestion.domain.observation.StopUpdate;
import pl.krktransit.realtime.ingestion.domain.observation.TripUpdateObservation;
import pl.krktransit.realtime.ingestion.domain.observation.VehicleLocation;
import pl.krktransit.realtime.ingestion.domain.observation.VehicleObservation;
import pl.krktransit.realtime.ingestion.support.Endpoints;
import pl.krktransit.realtime.ingestion.support.Observations;

class ObservationWireMapperTest {
    private final ObservationWireMapper mapper = new ObservationWireMapper();

    @Test
    void encodesVehiclePositionWithOriginAndOriginalIds() throws Exception {
        var wire = Observation.parseFrom(mapper.encode(Observations.vehicle(Endpoints.MPK_BUS, "trip_1")));

        assertThat(wire.getSource().getCityId()).isEqualTo("krakow");
        assertThat(wire.getSource().getSourceId()).isEqualTo("mpk-bus");
        assertThat(wire.getFetchedAt().getSeconds()).isEqualTo(Endpoints.FETCHED_AT.getEpochSecond());
        assertThat(wire.getFetchedAt().getNanos()).isEqualTo(123_456_789);
        assertThat(wire.getFeedTimestamp()).isEqualTo(1_761_387_300L);
        var vehicle = wire.getVehiclePosition();
        assertThat(vehicle.getTripId()).isEqualTo("trip_1");
        assertThat(vehicle.getLicensePlate()).isEqualTo("KR 1");
        assertThat(vehicle.getPosition().getLatitude()).isEqualTo(50.06f);
        assertThat(vehicle.getCurrentStatus()).isEqualTo(1);
        assertThat(vehicle.getTimestamp()).isEqualTo(1_761_387_290L);
    }

    @Test
    void absentFieldsHaveNoPresenceOnTheWire() throws Exception {
        var observation = new VehicleObservation(new ObservationOrigin(Endpoints.MPK_BUS, Endpoints.FETCHED_AT, null),
                null, null, null, null, null, null, null, null);

        var wire = Observation.parseFrom(mapper.encode(observation));

        assertThat(wire.hasFeedTimestamp()).isFalse();
        var vehicle = wire.getVehiclePosition();
        assertThat(vehicle.hasTripId()).isFalse();
        assertThat(vehicle.hasVehicleId()).isFalse();
        assertThat(vehicle.hasLicensePlate()).isFalse();
        assertThat(vehicle.hasPosition()).isFalse();
        assertThat(vehicle.hasStopId()).isFalse();
        assertThat(vehicle.hasCurrentStopSequence()).isFalse();
        assertThat(vehicle.hasCurrentStatus()).isFalse();
        assertThat(vehicle.hasTimestamp()).isFalse();
    }

    @Test
    void zeroValuesAndUnsignedBitsSurviveTheRoundTrip() throws Exception {
        var observation = new VehicleObservation(Endpoints.origin(Endpoints.MPK_BUS), "", null, "",
                new VehicleLocation(0f, null, 0f), null, -1, 0, -1L);

        var vehicle = Observation.parseFrom(mapper.encode(observation)).getVehiclePosition();

        assertThat(vehicle.hasTripId()).isTrue();
        assertThat(vehicle.getTripId()).isEmpty();
        assertThat(vehicle.hasCurrentStatus()).isTrue();
        assertThat(vehicle.getCurrentStatus()).isZero();
        assertThat(vehicle.getPosition().hasLatitude()).isTrue();
        assertThat(vehicle.getPosition().hasLongitude()).isFalse();
        assertThat(vehicle.getPosition().hasBearing()).isTrue();
        assertThat(Integer.toUnsignedLong(vehicle.getCurrentStopSequence())).isEqualTo(4_294_967_295L);
        assertThat(Long.toUnsignedString(vehicle.getTimestamp())).isEqualTo("18446744073709551615");
    }

    @Test
    void encodesTripUpdateStopsInOrderWithEventPresence() throws Exception {
        var observation = new TripUpdateObservation(Endpoints.origin(Endpoints.MPK_TRAM), "trip", 3, null, null,
                List.of(new StopUpdate(0, "a", new StopEvent(10L, null), null, null),
                        new StopUpdate(null, null, null, new StopEvent(null, -5), 1)));

        var update = Observation.parseFrom(mapper.encode(observation)).getTripUpdate();

        assertThat(update.getTripScheduleRelationship()).isEqualTo(3);
        assertThat(update.hasVehicleId()).isFalse();
        assertThat(update.hasTimestamp()).isFalse();
        assertThat(update.getStopTimeUpdatesList()).hasSize(2);
        var first = update.getStopTimeUpdates(0);
        assertThat(first.hasStopSequence()).isTrue();
        assertThat(first.getStopSequence()).isZero();
        assertThat(first.getArrival().getTime()).isEqualTo(10L);
        assertThat(first.getArrival().hasDelay()).isFalse();
        assertThat(first.hasDeparture()).isFalse();
        assertThat(first.hasScheduleRelationship()).isFalse();
        var second = update.getStopTimeUpdates(1);
        assertThat(second.hasStopSequence()).isFalse();
        assertThat(second.hasStopId()).isFalse();
        assertThat(second.hasArrival()).isFalse();
        assertThat(second.getDeparture().getDelay()).isEqualTo(-5);
        assertThat(second.getScheduleRelationship()).isEqualTo(1);
    }
}
