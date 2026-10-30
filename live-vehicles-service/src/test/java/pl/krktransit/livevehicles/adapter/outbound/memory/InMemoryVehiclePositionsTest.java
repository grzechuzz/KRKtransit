package pl.krktransit.livevehicles.adapter.outbound.memory;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.time.Instant;
import org.junit.jupiter.api.Test;
import pl.krktransit.livevehicles.domain.SourceId;
import pl.krktransit.livevehicles.domain.StopProgress;
import pl.krktransit.livevehicles.domain.StreamId;
import pl.krktransit.livevehicles.domain.VehiclePosition;
import pl.krktransit.livevehicles.domain.VehicleStopStatus;

class InMemoryVehiclePositionsTest {
    private static final Instant NOW = Instant.parse("2026-10-29T12:00:00Z");
    private final InMemoryVehiclePositions positions = new InMemoryVehiclePositions(Duration.ofMinutes(10), 10);

    @Test
    void positionsLongAfterTheirProviderTimeAreKeptUntilTheRetentionEnds() {
        positions.accept(position("mpk-bus", "A", NOW.minusSeconds(599), NOW.minusSeconds(599), "1-0"), NOW);
        positions.accept(position("mpk-bus", "B", NOW.minusSeconds(500), NOW, "2-0"), NOW);
        positions.accept(position("mpk-bus", "C", NOW.minusSeconds(600), NOW, "3-0"), NOW);
        assertThat(positions.snapshot(NOW)).extracting(VehiclePosition::licensePlate).containsExactly("A", "B");
        assertThat(positions.snapshot(NOW.plusSeconds(1))).extracting(VehiclePosition::licensePlate).containsExactly("B");
        assertThat(positions.snapshot(NOW.plusSeconds(600))).isEmpty();
    }

    @Test
    void positionsWithoutUsableProviderTimeAreRetainedFromTheirFetchTime() {
        var source = new SourceId("krakow", "mpk-bus");
        positions.accept(new VehiclePosition(source, null, null, "A", 50, 20, null, null, NOW, StreamId.parse("1-0"), StopProgress.NONE), NOW);
        positions.accept(position("mpk-bus", "B", NOW.plus(Duration.ofDays(365)), NOW, "2-0"), NOW);
        assertThat(positions.snapshot(NOW.plusSeconds(599))).extracting(VehiclePosition::licensePlate).containsExactly("A", "B");
        assertThat(positions.snapshot(NOW.plusSeconds(600))).isEmpty();
    }

    @Test
    void providerTimeThenFetchTimeThenStreamIdResolveOrdering() {
        var current = position("mpk-bus", "A", NOW.minusSeconds(1), NOW.minusSeconds(1), "2-0");
        positions.accept(current, NOW);
        positions.accept(position("mpk-bus", "A", NOW.minusSeconds(2), NOW, "3-0"), NOW);
        assertThat(positions.snapshot(NOW)).containsExactly(current);
        var fetchedLater = position("mpk-bus", "A", NOW.minusSeconds(1), NOW, "4-0");
        positions.accept(fetchedLater, NOW);
        positions.accept(current, NOW);
        var tiedLater = position("mpk-bus", "A", NOW.minusSeconds(1), NOW, "4-1");
        positions.accept(tiedLater, NOW);
        positions.accept(fetchedLater, NOW);
        assertThat(positions.snapshot(NOW)).containsExactly(tiedLater);
    }

    @Test
    void strictlyNewerPositionTimeMayCorrectProgressBackwards() {
        positions.accept(progressing("trip", 12, NOW.minusSeconds(1), NOW.minusSeconds(1), "1-0"), NOW);
        var correction = progressing("trip", 11, NOW, NOW, "2-0");
        positions.accept(correction, NOW);
        assertThat(positions.snapshot(NOW)).containsExactly(correction);
    }

    @Test
    void refetchedObservationWithTheSamePositionTimeCannotMoveProgressBackwards() {
        var current = progressing("trip", 12, NOW.minusSeconds(1), NOW.minusSeconds(1), "1-0");
        positions.accept(current, NOW);
        positions.accept(progressing("trip", 11, NOW.minusSeconds(1), NOW, "2-0"), NOW);
        assertThat(positions.snapshot(NOW)).containsExactly(current);
    }

    @Test
    void positionTimeTiesWithoutRegressionKeepTheFetchAndStreamTieBreak() {
        positions.accept(progressing("trip", 12, NOW.minusSeconds(1), NOW.minusSeconds(1), "1-0"), NOW);
        var sameSequence = progressing("trip", 12, NOW.minusSeconds(1), NOW, "2-0");
        positions.accept(sameSequence, NOW);
        assertThat(positions.snapshot(NOW)).containsExactly(sameSequence);
        var nextTrip = progressing("next", 1, NOW.minusSeconds(1), NOW, "3-0");
        positions.accept(nextTrip, NOW);
        assertThat(positions.snapshot(NOW)).containsExactly(nextTrip);
    }

    @Test
    void sourceScopesVehicleIdentityAndZeroCoordinatesArePreserved() {
        positions.accept(position("mpk-bus", "A", NOW, NOW, "1-0"), NOW);
        positions.accept(position("mobilis-bus", "A", NOW, NOW, "1-0"), NOW);
        assertThat(positions.snapshot(NOW)).hasSize(2).allSatisfy(position -> {
            assertThat(position.latitude()).isZero();
            assertThat(position.longitude()).isZero();
            assertThat(position.bearing()).isZero();
        });
    }

    @Test
    void vehicleIdCanIdentifyAPositionWithoutALicensePlate() {
        var source = new SourceId("krakow", "mpk-bus");
        positions.accept(new VehiclePosition(source, source.scope("trip"), "vehicle", null,
                50, 20, null, NOW, NOW, StreamId.parse("1-0"), StopProgress.NONE), NOW);
        assertThat(positions.snapshot(NOW)).singleElement().satisfies(position -> {
            assertThat(position.key().identity()).isEqualTo("vehicle");
            assertThat(position.licensePlate()).isNull();
            assertThat(position.bearing()).isNull();
        });
    }

    @Test
    void capacityIsBoundedAndExpiredEntriesAreRemovedBeforeEviction() {
        var small = new InMemoryVehiclePositions(Duration.ofMinutes(10), 2);
        small.accept(position("mpk-bus", "A", NOW.minusSeconds(598), NOW.minusSeconds(598), "1-0"), NOW);
        small.accept(position("mpk-bus", "B", NOW, NOW, "2-0"), NOW);
        small.accept(position("mpk-bus", "C", NOW.plusSeconds(2), NOW.plusSeconds(2), "3-0"), NOW.plusSeconds(2));
        small.accept(position("mpk-bus", "D", NOW.plusSeconds(3), NOW.plusSeconds(3), "4-0"), NOW.plusSeconds(3));
        assertThat(small.snapshot(NOW.plusSeconds(3))).extracting(VehiclePosition::licensePlate).containsExactly("C", "D");
    }

    private VehiclePosition progressing(String trip, int sequence, Instant timestamp, Instant fetchedAt, String id) {
        var source = new SourceId("krakow", "mpk-bus");
        return new VehiclePosition(source, source.scope(trip), "vehicle", "A", 0, 0, 0.0, timestamp, fetchedAt,
                StreamId.parse(id), new StopProgress(null, sequence, VehicleStopStatus.IN_TRANSIT_TO));
    }

    private VehiclePosition position(String sourceId, String plate, Instant timestamp, Instant fetchedAt, String id) {
        var source = new SourceId("krakow", sourceId);
        return new VehiclePosition(source, source.scope("trip"), "vehicle", plate, 0, 0, 0.0,
                timestamp, fetchedAt, StreamId.parse(id), StopProgress.NONE);
    }
}
