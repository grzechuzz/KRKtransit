package pl.krktransit.stopevents.application;

import static org.assertj.core.api.Assertions.assertThat;
import static pl.krktransit.stopevents.support.DetectorFixtures.*;

import java.time.Duration;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import pl.krktransit.stopevents.application.processing.ProcessObservationBatch;
import pl.krktransit.stopevents.application.processing.SourceBatch;
import pl.krktransit.stopevents.application.processing.StreamEntry;
import pl.krktransit.stopevents.domain.event.EventKey;
import pl.krktransit.stopevents.domain.observation.VehicleObservation;
import pl.krktransit.stopevents.domain.state.TripKey;
import pl.krktransit.stopevents.domain.state.TripPin;
import pl.krktransit.stopevents.domain.state.VehicleKey;
import pl.krktransit.stopevents.domain.state.VehicleState;
import pl.krktransit.stopevents.support.InMemoryPorts;

class VehicleObservationOrderingTest {
    private final InMemoryPorts.State state = new InMemoryPorts.State();
    private final InMemoryPorts.Events events = new InMemoryPorts.Events();
    private final InMemoryPorts.Schedule schedule = new InMemoryPorts.Schedule();
    private final ProcessObservationBatch process = new ProcessObservationBatch(state, events, schedule, Duration.ofHours(3));
    private final VehicleKey vehicle = new VehicleKey(SOURCE, "KR123");
    private int cursor;

    @Test
    void anOlderStoppedAtCanFillHistoryWithoutMovingCurrentStateBackwards() {
        var current = active(TRIP, 3, 125);
        var successor = event(3, 5, BASE.plusSeconds(125));
        events.rows.put(successor.key(), successor);
        observe(observation(TRIP, 2, 1, 90, 300));
        assertThat(events.rows).containsKey(new EventKey(TRIP, DATE, 2));
        assertThat(state.vehicles.get(vehicle)).isEqualTo(current);
        assertThat(state.positions.get(SOURCE)).isEqualTo("1-0");
    }

    @Test
    void aLateStoppedAtStillHasToPassTheStoredSuccessorValidation() {
        var current = active(TRIP, 3, 150);
        var successor = event(3, 0, BASE.plusSeconds(120));
        events.rows.put(successor.key(), successor);
        observe(observation(TRIP, 2, 1, 140, 300));
        assertThat(events.rows).containsOnlyKeys(successor.key());
        assertThat(state.vehicles.get(vehicle)).isEqualTo(current);
    }

    @ParameterizedTest
    @ValueSource(ints = {299, 300})
    void anOlderOrEqualTimeObservationOfAnotherTripCannotFinalizeTheActiveTrip(int timestamp) {
        String next = SOURCE.scope("next");
        var current = active(next, 2, 300);
        var activeTrip = new TripKey(SOURCE, next);
        state.forecasts.put(activeTrip, forecasts());
        state.pins.put(new TripKey(SOURCE, TRIP), new TripPin(HASH, BASE));
        observe(observation(TRIP, 1, 1, timestamp, 400));
        assertThat(state.vehicles.get(vehicle)).isEqualTo(current);
        assertThat(state.forecasts.get(activeTrip)).isEqualTo(forecasts());
        assertThat(events.rows.values()).noneMatch(event -> event.tripId().equals(next));
        assertThat(events.rows).containsKey(new EventKey(TRIP, DATE, 1));
    }

    @Test
    void anOlderMovingObservationDoesNotGenerateSequenceJumpEstimates() {
        var current = active(TRIP, 2, 150);
        state.forecasts.put(new TripKey(SOURCE, TRIP), forecasts());
        observe(observation(TRIP, 4, 2, 140, 300));
        assertThat(state.vehicles.get(vehicle)).isEqualTo(current);
        assertThat(events.rows).isEmpty();
        assertThat(schedule.contextHashes).isEmpty();
    }

    @Test
    void anEqualTimestampCanAddStoppedAtForTheSameOccurrence() {
        active(TRIP, 2, 90);
        observe(observation(TRIP, 2, 1, 90, 95));
        assertThat(events.rows).containsKey(new EventKey(TRIP, DATE, 2));
        assertThat(state.vehicles.get(vehicle).timestamp()).isEqualTo(BASE.plusSeconds(90));
        assertThat(state.vehicles.get(vehicle).fetchedAt()).isEqualTo(BASE.plusSeconds(95));
    }

    @Test
    void anEqualTimestampCanAdvanceWithinTheSameTripButCannotRegressItsSequence() {
        active(TRIP, 2, 120);
        observe(observation(TRIP, 3, 2, 120, 130));
        var advanced = state.vehicles.get(vehicle);
        assertThat(advanced.sequence()).isEqualTo(3);
        observe(observation(TRIP, 2, 1, 120, 140));
        assertThat(state.vehicles.get(vehicle)).isEqualTo(advanced);
    }

    @Test
    void aNewerTimestampTakesPrecedenceOverFetchTime() {
        active(TRIP, 2, 90);
        observe(observation(TRIP, 3, 2, 120, 90));
        assertThat(state.vehicles.get(vehicle).timestamp()).isEqualTo(BASE.plusSeconds(120));
        assertThat(state.vehicles.get(vehicle).sequence()).isEqualTo(3);
    }

    @Test
    void aNormalNewerTripSwitchStillFinalizesThePreviousTrip() {
        active(TRIP, 3, 150);
        state.forecasts.put(new TripKey(SOURCE, TRIP), forecasts());
        String next = SOURCE.scope("next");
        observe(observation(next, 1, 2, 300, 300));
        assertThat(state.vehicles.get(vehicle).tripId()).isEqualTo(next);
        assertThat(events.rows).containsKeys(new EventKey(TRIP, DATE, 3), new EventKey(TRIP, DATE, 4));
        assertThat(state.forecasts).doesNotContainKey(new TripKey(SOURCE, TRIP));
    }

    @Test
    void aLaterServiceDayCanRestartTheSameTripIdAtTheFirstStop() {
        active(TRIP, 4, 210);
        observe(observation(TRIP, 1, 2, 86400, 86400));
        assertThat(state.vehicles.get(vehicle).sequence()).isEqualTo(1);
        assertThat(state.vehicles.get(vehicle).timestamp()).isEqualTo(BASE.plusSeconds(86400));
    }

    @Test
    void anAlreadyStoredEstimateIsNotReplacedByALateDirectObservation() {
        active(TRIP, 3, 150);
        var stored = event(2, 10, BASE.plusSeconds(70));
        events.rows.put(stored.key(), stored);
        observe(observation(TRIP, 2, 1, 90, 300));
        assertThat(events.rows.get(stored.key())).isEqualTo(stored);
        assertThat(state.saved.get(stored.key())).isEqualTo(stored.savedStop());
    }

    private VehicleState active(String trip, int sequence, int timestamp) {
        var value = new VehicleState(trip, "KR123", sequence, BASE.plusSeconds(timestamp), BASE.plusSeconds(timestamp));
        state.vehicles.put(vehicle, value);
        state.pins.put(new TripKey(SOURCE, trip), new TripPin(HASH, BASE));
        return value;
    }

    private VehicleObservation observation(String trip, int sequence, int status, int timestamp, int fetched) {
        return new VehicleObservation(SOURCE, BASE.plusSeconds(fetched), trip, "KR123", "vehicle", sequence,
                status, BASE.plusSeconds(timestamp));
    }

    private void observe(VehicleObservation value) {
        process.process(List.of(new SourceBatch(SOURCE, List.of(new StreamEntry(++cursor + "-0", value)))));
    }
}
