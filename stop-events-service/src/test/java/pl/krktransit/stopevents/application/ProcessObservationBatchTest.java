package pl.krktransit.stopevents.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static pl.krktransit.stopevents.support.DetectorFixtures.*;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import pl.krktransit.stopevents.application.port.*;
import pl.krktransit.stopevents.application.processing.*;
import pl.krktransit.stopevents.domain.event.EventKey;
import pl.krktransit.stopevents.domain.observation.*;
import pl.krktransit.stopevents.domain.source.SourceId;
import pl.krktransit.stopevents.domain.state.*;
import pl.krktransit.stopevents.support.InMemoryPorts;

class ProcessObservationBatchTest {
    private final InMemoryPorts.State state = new InMemoryPorts.State();
    private final InMemoryPorts.Events events = new InMemoryPorts.Events();
    private final InMemoryPorts.Schedule schedule = new InMemoryPorts.Schedule();
    private final ProcessObservationBatch process = new ProcessObservationBatch(state, events, schedule, Duration.ofHours(3));
    private final TripKey tripKey = new TripKey(SOURCE, TRIP);
    private int nextId;

    @Test
    void pinsReachRedisBeforeSqlAndStateReachesRedisAfterSql() {
        events.beforeInsert = () -> {
            assertThat(state.pins).containsKey(tripKey);
            assertThat(state.operations).containsExactly("pins");
            assertThat(state.positions).isEmpty();
        };
        process.process(List.of(batch(vp(1, 1, 0), vp(1, 1, 10))));
        assertThat(events.rows).hasSize(1);
        assertThat(state.operations).containsExactly("pins", "commit");
        assertThat(state.saved).containsKey(new EventKey(TRIP, DATE, 1));
    }

    @Test
    void sqlCommitWithoutRedisCommitReplaysUsingTheOriginalPinAfterImport() {
        events.failAfterInsert = true;
        var batch = batch(vp(1, 1, 0));
        assertThatThrownBy(() -> process.process(List.of(batch))).isInstanceOf(StorageFailure.class);
        assertThat(state.positions).isEmpty();
        schedule.hash = "b".repeat(64);
        process.process(List.of(batch));
        process.process(List.of(batch(vp(2, 1, 60))));
        assertThat(events.rows).hasSize(2);
        assertThat(events.rows.values()).allMatch(event -> event.staticHash().equals(HASH));
        assertThat(state.saved.get(new EventKey(TRIP, DATE, 1))).isEqualTo(events.rows.get(new EventKey(TRIP, DATE, 1)).savedStop());
    }

    @Test
    void recoveryUsesSqlEvenWhenTheStoredStopIsNotRecomputed() {
        events.rows.put(new EventKey(TRIP, DATE, 2), event(2, 600, BASE.plusSeconds(60)));
        state.vehicles.put(new VehicleKey(SOURCE, "KR123"), previous(2));
        process.process(List.of(batch(vp(3, 1, 120))));
        assertThat(events.rows).containsOnlyKeys(new EventKey(TRIP, DATE, 2));
        assertThat(state.saved.get(new EventKey(TRIP, DATE, 2)).delaySeconds()).isEqualTo(600);
    }

    @Test
    void differingConflictRollsBackAndRevalidatesTheFollowingVpFromFreshState() {
        process.process(List.of(batch(vp(1, 1, 0))));
        events.rows.put(new EventKey(TRIP, DATE, 2), event(2, 600, BASE.plusSeconds(60)));
        process.process(List.of(batch(vp(2, 1, 60), vp(3, 1, 120))));
        assertThat(events.rolledBack).isEqualTo(1);
        assertThat(events.rows).doesNotContainKey(new EventKey(TRIP, DATE, 3));
        assertThat(state.saved.get(new EventKey(TRIP, DATE, 2)).delaySeconds()).isEqualTo(600);
        assertThat(state.vehicles.get(new VehicleKey(SOURCE, "KR123")).sequence()).isEqualTo(3);
    }

    @Test
    void aVpCannotUseAForecastThatAppearsLaterInTheStream() {
        state.vehicles.put(new VehicleKey(SOURCE, "KR123"), previous(1));
        process.process(List.of(batch(vp(3, 2, 120), tu(121, forecast(1, "A", 0), forecast(2, "B", 60)))));
        assertThat(events.rows).isEmpty();
    }

    @Test
    void aVpCanUsePrecedingForecastsInTheSameBatch() {
        state.vehicles.put(new VehicleKey(SOURCE, "KR123"), previous(1));
        process.process(List.of(batch(tu(0, forecast(1, "A", 0), forecast(2, "B", 60)), vp(3, 2, 120))));
        assertThat(events.rows.keySet()).extracting(EventKey::sequence).containsExactlyInAnyOrder(1, 2);
    }

    @Test
    void forecastExpiryDoesNotRepinATripWithContinuingVps() {
        process.process(List.of(batch(tu(0, forecast(2, "B", 60)), vp(1, 2, 1))));
        schedule.hash = "b".repeat(64);
        process.process(List.of(batch(vp(1, 2, 7200))));
        process.process(List.of(batch(vp(1, 2, 14400))));
        assertThat(state.pins.get(tripKey).hash()).isEqualTo(HASH);
        assertThat(state.pins.get(tripKey).fetchedAt()).isEqualTo(BASE.plusSeconds(14400));
        assertThat(state.forecasts).doesNotContainKey(tripKey);
    }

    @Test
    void inactivityAllowsANewPinForTheNextRun() {
        process.process(List.of(batch(vp(1, 2, 0))));
        schedule.hash = "b".repeat(64);
        process.process(List.of(batch(vp(1, 2, 10801))));
        assertThat(state.pins.get(tripKey).hash()).isEqualTo(schedule.hash);
    }

    @Test
    void anUnknownTripIsNotPinnedAndCanBecomeKnownAfterImport() {
        schedule.unknown = true;
        process.process(List.of(batch(vp(1, 1, 0))));
        assertThat(state.pins).isEmpty();
        schedule.unknown = false;
        schedule.hash = "b".repeat(64);
        process.process(List.of(batch(vp(1, 1, 10))));
        assertThat(state.pins.get(tripKey).hash()).isEqualTo(schedule.hash);
    }

    @Test
    void anInvalidContextSkipsTheObservationButAdvancesTheCursor() {
        schedule.invalid = true;
        var outcome = process.process(List.of(batch(vp(1, 1, 0))));
        assertThat(outcome.cursors()).containsKey(SOURCE);
        assertThat(events.rows).isEmpty();
        assertThat(state.pins).isEmpty();
    }

    @Test
    void recoveryReadsOnlyServiceDatesReachableFromTheBatch() {
        var old = new EventKey(TRIP, DATE.minusDays(3), 2);
        events.rows.put(old, event(2, 600, BASE.minusSeconds(3 * 86400)));
        process.process(List.of(batch(vp(1, 1, 0))));
        assertThat(events.recoveryStarts).containsExactly(DATE.minusDays(1));
        assertThat(state.saved).doesNotContainKey(old);
    }

    @Test
    void aVpWithoutATripDoesNotFinalizeOrChangeTheVehicle() {
        state.vehicles.put(new VehicleKey(SOURCE, "KR123"), previous(2));
        var invalid = new VehicleObservation(SOURCE, BASE, null, "KR123", "vehicle", 2, 1, BASE);
        process.process(List.of(batch(invalid)));
        assertThat(events.rows).isEmpty();
        assertThat(state.vehicles.get(new VehicleKey(SOURCE, "KR123"))).isEqualTo(previous(2));
    }

    @Test
    void scheduleFailureDiscardsOnlyItsSourceIncludingEarlierEntries() {
        var other = new SourceId("krakow", "mpk-tram");
        schedule.unavailable.add(SOURCE);
        var tram = new VehicleObservation(other, BASE, other.scope("tram"), "TR123", null, 1, 1, BASE);
        var outcome = process.process(List.of(batch(vp(1, 1, 0)), new SourceBatch(other, List.of(new StreamEntry("1-0", tram)))));
        assertThat(outcome.pausedSources()).containsExactly(SOURCE);
        assertThat(state.positions).containsOnlyKeys(other);
        assertThat(events.rows.values()).allMatch(event -> event.source().equals(other));
    }

    @Test
    void uncertainExecWithAnAppliedCommitDoesNotReprocessTheBatch() {
        state.failAfterCommit = true;
        var outcome = process.process(List.of(batch(vp(1, 1, 0))));
        assertThat(outcome.eventCount()).isEqualTo(1);
        assertThat(events.rows).hasSize(1);
        assertThat(state.positions).containsKey(SOURCE);
    }

    @Test
    void uncertainExecWithoutAnAppliedCommitRunsRecoveryOnRetry() {
        state.failBeforeCommit = true;
        var batch = batch(vp(1, 1, 0));
        assertThatThrownBy(() -> process.process(List.of(batch))).isInstanceOf(StorageFailure.class);
        process.process(List.of(batch));
        assertThat(events.rows).hasSize(1);
        assertThat(events.recoveryReads).hasSize(2);
    }

    @Test
    void partialExecIsNeverAcceptedAsATransientFailure() {
        state.corruptCommit = true;
        assertThatThrownBy(() -> process.process(List.of(batch(vp(1, 1, 0)))))
                .isInstanceOf(StateIntegrityFailure.class);
    }

    @Test
    void recoveryIncludesThePreviousTripThatWillBeFinalized() {
        state.vehicles.put(new VehicleKey(SOURCE, "KR123"), previous(3));
        state.pins.put(tripKey, new TripPin(HASH, BASE));
        state.forecasts.put(tripKey, forecasts());
        var next = new VehicleObservation(SOURCE, BASE.plusSeconds(300), SOURCE.scope("next"), "KR123", null,
                1, 2, BASE.plusSeconds(300));
        process.process(List.of(batch(next)));
        assertThat(events.recoveryReads.getFirst()).contains(TRIP, SOURCE.scope("next"));
        assertThat(events.rows.keySet()).extracting(EventKey::sequence).containsExactlyInAnyOrder(3, 4);
        assertThat(state.pins).containsKey(tripKey);
        assertThat(state.forecasts).doesNotContainKey(tripKey);
    }

    @Test
    void redisReadFailureAlsoRequiresSqlRecoveryBeforeTheNextObservation() {
        process.process(List.of(batch(vp(1, 1, 0))));
        events.rows.put(new EventKey(TRIP, DATE, 2), event(2, 600, BASE.plusSeconds(60)));
        state.saved.clear();
        state.vehicles.clear();
        process.requireRecovery(java.util.Set.of(SOURCE));
        process.process(List.of(batch(vp(3, 1, 120))));
        assertThat(events.rows).doesNotContainKey(new EventKey(TRIP, DATE, 3));
        assertThat(events.recoveryReads).hasSize(2);
    }

    @Test
    void unavailablePinnedVersionDoesNotCauseRepinningWhileVpsContinue() {
        process.process(List.of(batch(vp(1, 2, 0))));
        schedule.versionUnavailable = true;
        schedule.hash = "b".repeat(64);
        process.process(List.of(batch(vp(1, 2, 7200))));
        process.process(List.of(batch(vp(1, 2, 14400))));
        assertThat(state.pins.get(tripKey).hash()).isEqualTo(HASH);
        assertThat(state.pins.get(tripKey).fetchedAt()).isEqualTo(BASE.plusSeconds(14400));
        assertThat(schedule.hashReads).isEqualTo(1);
    }

    private SourceBatch batch(Observation... observations) {
        return new SourceBatch(SOURCE, java.util.Arrays.stream(observations)
                .map(value -> new StreamEntry(++nextId + "-0", value)).toList());
    }
}
