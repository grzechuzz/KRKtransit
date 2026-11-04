package pl.krktransit.livevehicles.adapter.inbound.stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.google.protobuf.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import pl.krktransit.livevehicles.adapter.outbound.memory.InMemoryTripForecasts;
import pl.krktransit.livevehicles.domain.SourceId;
import pl.krktransit.livevehicles.domain.StopForecast;
import pl.krktransit.livevehicles.domain.StopProgress;
import pl.krktransit.livevehicles.domain.TripForecast;
import pl.krktransit.livevehicles.domain.VehicleStopStatus;
import pl.krktransit.realtime.contracts.v1.Observation;
import pl.krktransit.realtime.contracts.v1.Position;
import pl.krktransit.realtime.contracts.v1.Source;
import pl.krktransit.realtime.contracts.v1.StopTimeEvent;
import pl.krktransit.realtime.contracts.v1.StopTimeUpdate;
import pl.krktransit.realtime.contracts.v1.TripUpdate;
import pl.krktransit.realtime.contracts.v1.VehiclePosition;

class ObservationWireMapperTest {
    private static final SourceId SOURCE = new SourceId("krakow", "mpk-bus");
    private final ObservationWireMapper mapper = new ObservationWireMapper();

    @Test
    void coordinatesNeedNoStopStatusOrSequenceAndPreserveZerosAndOriginalTripBytes() throws Exception {
        var observation = envelope().setVehiclePosition(vehicle().setTripId(" 000:trip ")).build();
        assertThat(position(observation.toByteArray())).get().satisfies(position -> {
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
        assertThat(position(observation.toByteArray())).get().satisfies(position -> {
            assertThat(position.tripId()).isNull();
            assertThat(position.timestamp()).isNull();
            assertThat(position.bearing()).isNull();
            assertThat(position.observedAt()).isEqualTo(Instant.ofEpochSecond(100));
        });
        assertThat(position(envelope().setVehiclePosition(vehicle()
                .setTimestamp(31556889864403199L)).build().toByteArray())).get()
                .satisfies(position -> assertThat(position.timestamp()).isNull());
    }

    @Test
    void stopProgressIsCopiedWithScopedStopIdAndZeroValuesPreserved() throws Exception {
        var observation = envelope().setVehiclePosition(vehicle().setStopId(" 0042 ").setCurrentStopSequence(0)
                .setCurrentStatus(0)).build();
        assertThat(position(observation.toByteArray())).get().satisfies(position ->
                assertThat(position.progress()).isEqualTo(
                        new StopProgress("krakow:mpk-bus: 0042 ", 0, VehicleStopStatus.INCOMING_AT)));
    }

    @Test
    void absentProgressStaysAbsentAndBlankStopIdsAreDropped() throws Exception {
        assertThat(position(envelope().setVehiclePosition(vehicle()).build().toByteArray())).get()
                .satisfies(position -> assertThat(position.progress()).isEqualTo(StopProgress.NONE));
        assertThat(position(envelope().setVehiclePosition(vehicle().setStopId(" ")
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
    void positionsWithoutCoordinatesOrIdentityAreIgnored() throws Exception {
        assertThat(position(envelope().setVehiclePosition(vehicle().clearPosition()).build().toByteArray())).isEmpty();
        assertThat(position(envelope().setVehiclePosition(vehicle().clearLicensePlate()).build().toByteArray())).isEmpty();
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

    @Test
    void tripUpdatesBecomeScopedForecastsAndKeepZeroSequences() throws Exception {
        var observation = envelope().setTripUpdate(TripUpdate.newBuilder().setTripId(" 000:trip ")
                .addStopTimeUpdates(arrival(200).setStopSequence(0).setStopId(" 0042 "))
                .addStopTimeUpdates(arrival(300).setStopId("0043"))
                .addStopTimeUpdates(arrival(400).setStopSequence(9))).build();
        assertThat(mapper.decode(SOURCE, "123-1", observation.toByteArray())).contains(new TripForecast(
                "krakow:mpk-bus: 000:trip ", Instant.ofEpochSecond(100), List.of(
                new StopForecast(0, "krakow:mpk-bus: 0042 ", Instant.ofEpochSecond(200)),
                new StopForecast(null, "krakow:mpk-bus:0043", Instant.ofEpochSecond(300)),
                new StopForecast(9, null, Instant.ofEpochSecond(400)))));
    }

    @Test
    void forecastStopsWithoutUsableArrivalTimeOrIdentityAreDropped() throws Exception {
        var observation = envelope().setTripUpdate(TripUpdate.newBuilder().setTripId("trip")
                .addStopTimeUpdates(StopTimeUpdate.newBuilder().setStopId("no-arrival"))
                .addStopTimeUpdates(StopTimeUpdate.newBuilder().setStopId("delay-only")
                        .setArrival(StopTimeEvent.newBuilder().setDelay(60)))
                .addStopTimeUpdates(arrival(0).setStopId("zero-time"))
                .addStopTimeUpdates(arrival(253402300800L).setStopId("beyond-range"))
                .addStopTimeUpdates(arrival(200).setStopId(" "))
                .addStopTimeUpdates(arrival(200).setStopSequence(-1))).build();
        assertThat(mapper.decode(SOURCE, "123-1", observation.toByteArray())).contains(
                new TripForecast("krakow:mpk-bus:trip", Instant.ofEpochSecond(100), List.of()));
    }

    @Test
    void tripUpdatesWithoutUsableTripIdOrFetchTimeAreIgnored() throws Exception {
        assertThat(mapper.decode(SOURCE, "123-1", envelope().setTripUpdate(TripUpdate.newBuilder()).build().toByteArray())).isEmpty();
        assertThat(mapper.decode(SOURCE, "123-1", envelope().setTripUpdate(TripUpdate.newBuilder().setTripId(" ")).build().toByteArray())).isEmpty();
        assertThat(mapper.decode(SOURCE, "123-1", envelope().clearFetchedAt().setTripUpdate(TripUpdate.newBuilder().setTripId("trip")).build().toByteArray())).isEmpty();
        assertThat(mapper.decode(SOURCE, "123-1", envelope().build().toByteArray())).isEmpty();
    }

    @ParameterizedTest
    @ValueSource(ints = {3, 7})
    void removedTripsHaveNoForecastEvenWhenTheyCarryTimes(int relationship) throws Exception {
        assertThat(mapper.decode(SOURCE, "123-1", tripUpdate(relationship, 100))).contains(
                new TripForecast("krakow:mpk-bus:trip", Instant.ofEpochSecond(100), List.of()));
    }

    @Test
    void cancellationClearsThePreviousForecastOfTheTrip() throws Exception {
        var forecasts = new InMemoryTripForecasts(Duration.ofMinutes(5), 10);
        var now = Instant.ofEpochSecond(101);
        forecasts.accept((TripForecast) mapper.decode(SOURCE, "123-1", tripUpdate(0, 100)).orElseThrow(), now);
        assertThat(forecasts.find("krakow:mpk-bus:trip", now)).get().satisfies(forecast -> assertThat(forecast.stops()).hasSize(1));
        forecasts.accept((TripForecast) mapper.decode(SOURCE, "123-2", tripUpdate(3, 101)).orElseThrow(), now);
        assertThat(forecasts.find("krakow:mpk-bus:trip", now)).get().satisfies(forecast -> assertThat(forecast.stops()).isEmpty());
    }

    @Test
    void addedAndDuplicatedTripsKeepTheirForecast() throws Exception {
        assertThat(mapper.decode(SOURCE, "123-1", tripUpdate(1, 100))).get().satisfies(observation ->
                assertThat(((TripForecast) observation).stops()).hasSize(1));
        assertThat(mapper.decode(SOURCE, "123-1", tripUpdate(6, 100))).get().satisfies(observation ->
                assertThat(((TripForecast) observation).stops()).hasSize(1));
    }

    @Test
    void skippedAndNoDataStopsAreDroppedWhileOtherStopsStay() throws Exception {
        var observation = envelope().setTripUpdate(TripUpdate.newBuilder().setTripId("trip")
                .addStopTimeUpdates(arrival(200).setStopId("skipped").setScheduleRelationship(1))
                .addStopTimeUpdates(arrival(300).setStopId("no-data").setScheduleRelationship(2))
                .addStopTimeUpdates(arrival(400).setStopId("scheduled").setScheduleRelationship(0))
                .addStopTimeUpdates(arrival(500).setStopId("absent"))).build();
        assertThat(mapper.decode(SOURCE, "123-1", observation.toByteArray())).contains(new TripForecast(
                "krakow:mpk-bus:trip", Instant.ofEpochSecond(100), List.of(
                new StopForecast(null, "krakow:mpk-bus:scheduled", Instant.ofEpochSecond(400)),
                new StopForecast(null, "krakow:mpk-bus:absent", Instant.ofEpochSecond(500)))));
    }

    private byte[] tripUpdate(int relationship, long fetchedSeconds) {
        return envelope().setFetchedAt(Timestamp.newBuilder().setSeconds(fetchedSeconds))
                .setTripUpdate(TripUpdate.newBuilder().setTripId("trip").setTripScheduleRelationship(relationship)
                        .addStopTimeUpdates(arrival(200).setStopId("stop"))).build().toByteArray();
    }

    private StopTimeUpdate.Builder arrival(long seconds) {
        return StopTimeUpdate.newBuilder().setArrival(StopTimeEvent.newBuilder().setTime(seconds));
    }

    private Optional<pl.krktransit.livevehicles.domain.VehiclePosition> position(byte[] payload) throws Exception {
        return mapper.decode(SOURCE, "123-1", payload).map(pl.krktransit.livevehicles.domain.VehiclePosition.class::cast);
    }

    private VehicleStopStatus status(int code) throws Exception {
        var observation = envelope().setVehiclePosition(vehicle().setCurrentStatus(code)).build();
        return position(observation.toByteArray()).orElseThrow().progress().status();
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
