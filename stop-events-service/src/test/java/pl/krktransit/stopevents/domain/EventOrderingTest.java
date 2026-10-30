package pl.krktransit.stopevents.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static pl.krktransit.stopevents.support.DetectorFixtures.*;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Random;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import pl.krktransit.stopevents.domain.detection.EventValidator;
import pl.krktransit.stopevents.domain.event.EventKey;
import pl.krktransit.stopevents.domain.event.StopEvent;
import pl.krktransit.stopevents.domain.state.SavedStop;
import pl.krktransit.stopevents.domain.state.SavedStops;

class EventOrderingTest {
    private final EventValidator validator = new EventValidator();

    @ParameterizedTest
    @ValueSource(ints = {2, 3})
    void aLatePredecessorMustOccurBeforeTheAlreadyStoredSuccessor(int successor) {
        var saved = saved(successor, 0, 120);
        assertThat(validator.validate(List.of(event(1, 0, BASE.plusSeconds(120))), saved)).isEmpty();
        assertThat(validator.validate(List.of(event(1, 0, BASE.plusSeconds(121))), saved)).isEmpty();
        assertThat(validator.validate(List.of(event(1, 0, BASE.plusSeconds(119))), saved)).hasSize(1);
    }

    @Test
    void successorDelayDropHasTheSameInclusiveBoundaryAsPredecessorDelayDrop() {
        var saved = saved(3, 20, 120);
        assertThat(validator.validate(List.of(event(2, 200, BASE.plusSeconds(60))), saved)).hasSize(1);
        assertThat(validator.validate(List.of(event(2, 201, BASE.plusSeconds(60))), saved)).isEmpty();
        assertThat(validator.validate(List.of(event(2, -100, BASE.plusSeconds(60))), saved)).hasSize(1);
    }

    @Test
    void aMissingImmediatePredecessorDoesNotBypassChronology() {
        var saved = saved(1, 200, 0);
        assertThat(validator.validate(List.of(event(3, 200, BASE)), saved)).isEmpty();
        assertThat(validator.validate(List.of(event(3, 19, BASE.plusSeconds(120))), saved)).hasSize(1);
    }

    @Test
    void recoveryOfMoreThanThreeMinutesAcrossMissingStopsKeepsTheExistingDelayRule() {
        var before = saved(1, 500, 0);
        var after = saved(4, 0, 180);
        assertThat(validator.validate(List.of(event(3, 0, BASE.plusSeconds(120))), before)).hasSize(1);
        assertThat(validator.validate(List.of(event(2, 500, BASE.plusSeconds(60))), after)).hasSize(1);
    }

    @Test
    void bothNeighborsConstrainAnInsertedStopWithoutRewritingEither() {
        var saved = new SavedStops(Map.of(new EventKey(TRIP, DATE, 1), new SavedStop(300, BASE),
                new EventKey(TRIP, DATE, 3), new SavedStop(0, BASE.plusSeconds(180))));
        assertThat(validator.validate(List.of(event(2, 119, BASE.plusSeconds(60))), saved)).isEmpty();
        assertThat(validator.validate(List.of(event(2, 181, BASE.plusSeconds(60))), saved)).isEmpty();
        assertThat(validator.validate(List.of(event(2, 120, BASE.plusSeconds(60))), saved)).hasSize(1);
        assertThat(saved.values()).hasSize(2);
    }

    @Test
    void aSuccessorAcceptedEarlierInTheBatchAlsoConstrainsALatePredecessor() {
        var accepted = validator.validate(List.of(event(3, 0, BASE.plusSeconds(120)),
                event(1, 0, BASE.plusSeconds(180))), emptySaved());
        assertThat(accepted).extracting(StopEvent::sequence).containsExactly(3);
    }

    @Test
    void neighboringSequencesFromOtherTripsAndServiceDatesAreIndependent() {
        var saved = new SavedStops(Map.of(
                new EventKey(SOURCE.scope("other"), DATE, 1), new SavedStop(1000, BASE.plusSeconds(300)),
                new EventKey(TRIP, DATE.minusDays(1), 3), new SavedStop(-180, BASE)));
        assertThat(validator.validate(List.of(event(2, 0, BASE.plusSeconds(60))), saved)).hasSize(1);
    }

    @ParameterizedTest
    @ValueSource(longs = {1, 17, 12345})
    void consistentHistoryCanArriveInAnyOrder(long seed) {
        var input = new ArrayList<>(List.of(event(1, 30, BASE), event(2, 20, BASE.plusSeconds(60)),
                event(3, 10, BASE.plusSeconds(120)), event(4, 0, BASE.plusSeconds(180))));
        Collections.shuffle(input, new Random(seed));
        assertThat(validator.validate(input, emptySaved())).containsExactlyElementsOf(input);
    }

    private SavedStops saved(int sequence, int delay, int seconds) {
        return new SavedStops(Map.of(new EventKey(TRIP, DATE, sequence),
                new SavedStop(delay, BASE.plusSeconds(seconds))));
    }
}
