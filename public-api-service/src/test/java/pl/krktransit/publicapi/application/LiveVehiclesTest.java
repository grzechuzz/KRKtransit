package pl.krktransit.publicapi.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.groups.Tuple.tuple;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import org.junit.jupiter.api.Test;
import pl.krktransit.publicapi.application.schedule.FeedId;
import pl.krktransit.publicapi.application.schedule.ScheduleSource;
import pl.krktransit.publicapi.application.schedule.Shape;
import pl.krktransit.publicapi.application.schedule.TripStops;
import pl.krktransit.publicapi.application.schedule.TripSummaries;
import pl.krktransit.publicapi.application.schedule.TripSummary;
import pl.krktransit.publicapi.application.vehicles.LiveVehicle;
import pl.krktransit.publicapi.application.vehicles.LiveVehicles;
import pl.krktransit.publicapi.application.vehicles.PositionSnapshot;
import pl.krktransit.publicapi.application.vehicles.StopProgress;
import pl.krktransit.publicapi.application.vehicles.VehiclePosition;
import pl.krktransit.publicapi.application.vehicles.VehiclePositionsSource;
import pl.krktransit.publicapi.support.MutableClock;

class LiveVehiclesTest {
    private static final Instant NOW = Instant.parse("2026-10-30T10:00:00Z");
    private static final FeedId BUS = new FeedId("krakow", "mpk-bus");
    private static final FeedId TRAM = new FeedId("krakow", "mpk-tram");
    private final MutableClock clock = new MutableClock(NOW);
    private final Positions positions = new Positions();
    private final Schedule schedule = new Schedule();
    private final PositionSnapshot snapshot = new PositionSnapshot(positions, Duration.ofSeconds(3), clock);
    private final LiveVehicles vehicles = new LiveVehicles(snapshot, schedule);

    @Test
    void enrichesPositionsInObservedOrderAndOmitsTripsUnknownInTheCurrentSchedule() {
        schedule.hashes.put(BUS, "bus-hash");
        schedule.hashes.put(TRAM, "tram-hash");
        schedule.trips.put("bus-hash/krakow:mpk-bus:1", new TripSummary("194", "Krowodrza", "krakow:mpk-bus:s1"));
        schedule.trips.put("tram-hash/krakow:mpk-tram:7", new TripSummary("4", null, null));
        positions.current = List.of(position(TRAM, "krakow:mpk-tram:7", "RP001"), position(BUS, "krakow:mpk-bus:unknown", "DN002"),
                position(BUS, "krakow:mpk-bus:1", "DN041"));

        assertThat(vehicles.current()).extracting(LiveVehicle::licensePlate, LiveVehicle::lineNumber, LiveVehicle::headsign,
                LiveVehicle::shapeId).containsExactly(tuple("RP001", "4", null, null),
                tuple("DN041", "194", "Krowodrza", "krakow:mpk-bus:s1"));
        assertThat(schedule.requestedTrips).containsExactlyInAnyOrder(Set.of("krakow:mpk-bus:unknown", "krakow:mpk-bus:1"),
                Set.of("krakow:mpk-tram:7"));
    }

    @Test
    void reusesOneSnapshotForItsLifetime() {
        schedule.hashes.put(BUS, "bus-hash");
        schedule.trips.put("bus-hash/krakow:mpk-bus:1", new TripSummary("194", "A", null));
        positions.current = List.of(position(BUS, "krakow:mpk-bus:1", "DN041"));

        vehicles.current();
        clock.advance(Duration.ofMillis(2_999));
        vehicles.current();
        assertThat(positions.calls).isOne();

        clock.advance(Duration.ofMillis(1));
        vehicles.current();
        assertThat(positions.calls).isEqualTo(2);
        assertThat(schedule.requestedTrips).hasSize(2);
    }

    @Test
    void tripLookupsShareTheRawSnapshotWithoutEnrichingTheMap() {
        schedule.hashes.put(BUS, "bus-hash");
        positions.current = List.of(position(BUS, "krakow:mpk-bus:1", "DN041"),
                new VehiclePosition(BUS, "krakow:mpk-bus:1", "DN042", 50, 19, null, NOW, StopProgress.NONE),
                position(BUS, "krakow:mpk-bus:2", "DN043"));

        assertThat(snapshot.newestOnTrip("krakow:mpk-bus:1")).get().extracting(VehiclePosition::licensePlate)
                .isEqualTo("DN042");
        assertThat(snapshot.newestOnTrip("krakow:mpk-bus:missing")).isEmpty();
        assertThat(schedule.requestedTrips).isEmpty();
        vehicles.current();
        assertThat(positions.calls).isOne();
        assertThat(schedule.requestedTrips).hasSize(1);
    }

    @Test
    void enrichmentFollowsEachNewRawSnapshot() {
        schedule.hashes.put(BUS, "bus-hash");
        schedule.trips.put("bus-hash/krakow:mpk-bus:1", new TripSummary("194", "A", null));
        positions.current = List.of(position(BUS, "krakow:mpk-bus:1", "DN041"));
        snapshot.current();
        clock.advance(Duration.ofSeconds(3));
        positions.current = List.of(position(BUS, "krakow:mpk-bus:1", "DN050"));

        assertThat(vehicles.current()).extracting(LiveVehicle::licensePlate).containsExactly("DN050");
        assertThat(positions.calls).isEqualTo(2);
    }

    @Test
    void omitsOnlyTheSourceWhoseScheduleVersionCannotBeResolved() {
        schedule.hashes.put(BUS, "bus-hash");
        schedule.trips.put("bus-hash/krakow:mpk-bus:1", new TripSummary("194", "A", null));
        schedule.unavailable.add(TRAM);
        positions.current = List.of(position(TRAM, "krakow:mpk-tram:7", "RP001"), position(BUS, "krakow:mpk-bus:1", "DN041"));

        assertThat(vehicles.current()).extracting(LiveVehicle::licensePlate).containsExactly("DN041");
    }

    @Test
    void positionFailuresReachTheCallerAndAreNotCached() {
        positions.failure = new ApiFailure.UpstreamUnavailable("Live vehicles service", null);

        assertThatThrownBy(vehicles::current).isInstanceOf(ApiFailure.UpstreamUnavailable.class);
        positions.failure = null;
        assertThat(vehicles.current()).isEmpty();
    }

    private static VehiclePosition position(FeedId feed, String tripId, String plate) {
        return new VehiclePosition(feed, tripId, plate, 50.06, 19.94, 90.0, NOW.minusSeconds(5), StopProgress.NONE);
    }

    private static final class Positions implements VehiclePositionsSource {
        private List<VehiclePosition> current = List.of();
        private ApiFailure failure;
        private int calls;

        @Override
        public List<VehiclePosition> positions() {
            calls++;
            if (failure != null) throw failure;
            return current;
        }
    }

    private static final class Schedule implements ScheduleSource {
        private final Map<FeedId, String> hashes = new HashMap<>();
        private final Map<String, TripSummary> trips = new HashMap<>();
        private final List<FeedId> unavailable = new ArrayList<>();
        private final List<Set<String>> requestedTrips = new ArrayList<>();

        @Override
        public Optional<String> currentHash(FeedId feed) {
            if (unavailable.contains(feed)) throw new ApiFailure.UpstreamUnavailable("Schedule service", null);
            return Optional.ofNullable(hashes.get(feed));
        }

        @Override
        public TripSummaries tripSummaries(String hash, Set<String> tripIds) {
            requestedTrips.add(tripIds);
            var found = new HashMap<String, TripSummary>();
            tripIds.forEach(tripId -> Optional.ofNullable(trips.get(hash + "/" + tripId)).ifPresent(trip -> found.put(tripId, trip)));
            return new TripSummaries(found, Set.of());
        }

        @Override
        public Optional<TripStops> tripStops(String hash, String tripId) { return Optional.empty(); }

        @Override
        public Optional<Shape> shape(String hash, String shapeId) { return Optional.empty(); }
    }
}
