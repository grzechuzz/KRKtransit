package pl.krktransit.stopevents.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static pl.krktransit.stopevents.support.DetectorFixtures.*;

import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullSource;
import org.junit.jupiter.params.provider.ValueSource;
import pl.krktransit.stopevents.domain.detection.*;
import pl.krktransit.stopevents.domain.event.*;
import pl.krktransit.stopevents.domain.observation.VehicleObservation;
import pl.krktransit.stopevents.domain.state.*;

class VehicleDetectorTest {
    private final VehicleDetector detector = new VehicleDetector();

    @Test
    void directStopUsesTheVehicleTimeAndPreservesDirectionZero() {
        var result = detector.detect(vp(2, 1, 90), null, trip(), null, emptySaved());
        assertThat(result.events()).singleElement().satisfies(event -> {
            assertThat(event.method()).isEqualTo(DetectionMethod.STOPPED_AT);
            assertThat(event.estimated()).isFalse();
            assertThat(event.delaySeconds()).isEqualTo(30);
            assertThat(event.directionId()).isZero();
        });
        assertThat(result.vehicleState().sequence()).isEqualTo(2);
    }

    @ParameterizedTest
    @NullSource
    @ValueSource(ints = {0, 2, 99})
    void onlyStoppedAtCreatesDirectEvents(Integer status) {
        assertThat(detector.detect(vp(2, status, 90), null, trip(), null, emptySaved()).events()).isEmpty();
    }

    @Test
    void jumpStartsAtPreviousSequenceAndUsesLastForecast() {
        var result = detector.detect(vp(4, 2, 210), previous(2), trip(), forecasts(), emptySaved());
        assertThat(result.events()).extracting(StopEvent::sequence).containsExactly(2, 3);
        assertThat(result.events().getFirst().eventTime()).isEqualTo(BASE.plusSeconds(70));
        assertThat(result.events()).allMatch(StopEvent::estimated);
    }

    @Test
    void savedStopsAndMissingForecastsAreSkipped() {
        var saved = new SavedStops(Map.of(new EventKey(TRIP, DATE, 2), new SavedStop(10, BASE.plusSeconds(70))));
        assertThat(detector.detect(vp(4, 2, 210), previous(2), trip(), forecasts(), saved).events())
                .extracting(StopEvent::sequence).containsExactly(3);
        assertThat(detector.detect(vp(4, 2, 210), previous(2), trip(), null, emptySaved()).events()).isEmpty();
    }

    @ParameterizedTest
    @ValueSource(ints = {1, 2})
    void equalOrDecreasingSequenceDoesNotJump(int seq) {
        assertThat(detector.detect(vp(seq, 2, 210), previous(2), trip(), forecasts(), emptySaved()).events()).isEmpty();
    }

    @Test
    void incompleteContextSkipsEventsButTracksVehicle() {
        for (var context : List.of(trip(HASH, TRIP, null, "Destination"), trip(HASH, TRIP, 0, " "))) {
            var result = detector.detect(vp(2, 1, 90), null, context, null, emptySaved());
            assertThat(result.events()).isEmpty();
            assertThat(result.vehicleState()).isNotNull();
        }
    }

    @Test
    void missingTripAndSequenceAreIneligibleAndMissingStopDoesNotAdvanceVehicle() {
        var missingTrip = new VehicleObservation(SOURCE, BASE, null, "KR123", null, 2, 1, BASE);
        assertThat(missingTrip.eligible()).isFalse();
        assertThat(detector.detect(vp(99, 1, 90), previous(2), trip(), forecasts(), emptySaved()).vehicleState()).isNull();
    }

    @Test
    void finalizationIncludesTheLastReportedSequenceAndUsesFirstForecastForTerminal() {
        var events = new TripFinalizer().finalizeTrip(SOURCE, previous(3), trip(), forecasts(), emptySaved(),
                BASE.plusSeconds(300));
        assertThat(events).extracting(StopEvent::sequence).containsExactly(3, 4);
        assertThat(events.getLast().eventTime()).isEqualTo(BASE.plusSeconds(180));
        assertThat(events.getLast().method()).isEqualTo(DetectionMethod.TIMEOUT);
        assertThat(events).allMatch(event -> event.vehicleId() == null);
    }

    @Test
    void finalizationSkipsForecastsLaterThanTheTriggeringObservation() {
        var events = new TripFinalizer().finalizeTrip(SOURCE, previous(2), trip(), forecasts(), emptySaved(),
                BASE.plusSeconds(150));
        assertThat(events).extracting(StopEvent::sequence).containsExactly(2, 3);
    }

    @Test
    void aVehicleThatNeverLeftTheFirstStopIsReassignedNotFinalized() {
        var events = new TripFinalizer().finalizeTrip(SOURCE, previous(1), trip(), forecasts(), emptySaved(),
                BASE.plusSeconds(300));
        assertThat(events).isEmpty();
    }

    @Test
    void sequenceJumpSkipsForecastsLaterThanTheObservation() {
        var result = detector.detect(vp(4, 2, 100), previous(2), trip(), forecasts(), emptySaved());
        assertThat(result.events()).extracting(StopEvent::sequence).containsExactly(2);
    }

    @Test
    void rejectedEstimatedEventsNeverBecomeSaved() {
        var result = detector.detect(vp(3, 1, 125), previous(2), trip(),
                new TripForecasts(Map.of(2, new RetainedForecast(SOURCE.scope("B"), BASE, BASE.plusSeconds(140))), 2, BASE),
                emptySaved());
        assertThat(result.events()).extracting(StopEvent::sequence).containsExactly(3);
    }
}
