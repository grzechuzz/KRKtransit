package pl.krktransit.publicapi.application.vehicles;

import static java.util.stream.Collectors.groupingBy;
import static java.util.stream.Collectors.mapping;
import static java.util.stream.Collectors.toMap;
import static java.util.stream.Collectors.toSet;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import pl.krktransit.publicapi.application.ApiFailure;
import pl.krktransit.publicapi.application.schedule.FeedId;
import pl.krktransit.publicapi.application.schedule.ScheduleSource;
import pl.krktransit.publicapi.application.schedule.TripSummary;

public final class LiveVehicles {
    private static final System.Logger LOG = System.getLogger(LiveVehicles.class.getName());
    private final VehiclePositionsSource positions;
    private final ScheduleSource schedule;
    private final Duration snapshotLifetime;
    private final Clock clock;
    private Snapshot snapshot;

    public LiveVehicles(VehiclePositionsSource positions, ScheduleSource schedule, Duration snapshotLifetime, Clock clock) {
        this.positions = positions;
        this.schedule = schedule;
        this.snapshotLifetime = snapshotLifetime;
        this.clock = clock;
    }

    public synchronized List<LiveVehicle> current() {
        if (!isFresh(snapshot)) snapshot = new Snapshot(load(), clock.instant());
        return snapshot.vehicles();
    }

    private boolean isFresh(Snapshot candidate) {
        return candidate != null && clock.instant().isBefore(candidate.takenAt().plus(snapshotLifetime));
    }

    private List<LiveVehicle> load() {
        var observed = positions.positions();
        var trips = tripsByFeed(observed);
        return observed.stream().flatMap(position -> enriched(position, trips).stream()).toList();
    }

    private Map<FeedId, Map<String, TripSummary>> tripsByFeed(List<VehiclePosition> observed) {
        var tripIds = observed.stream().collect(groupingBy(VehiclePosition::feed, mapping(VehiclePosition::tripId, toSet())));
        return tripIds.entrySet().stream().collect(toMap(Map.Entry::getKey, entry -> trips(entry.getKey(), entry.getValue())));
    }

    private Map<String, TripSummary> trips(FeedId feed, Set<String> tripIds) {
        return currentHash(feed).map(hash -> schedule.tripSummaries(hash, tripIds).found()).orElse(Map.of());
    }

    private Optional<String> currentHash(FeedId feed) {
        try {
            return schedule.currentHash(feed);
        } catch (ApiFailure.UpstreamUnavailable | ApiFailure.UpstreamBusy failure) {
            LOG.log(System.Logger.Level.WARNING, "Omitting vehicles of {0}:{1}: {2}", feed.cityId(), feed.sourceId(),
                    failure.getMessage());
            return Optional.empty();
        }
    }

    private Optional<LiveVehicle> enriched(VehiclePosition position, Map<FeedId, Map<String, TripSummary>> trips) {
        return Optional.ofNullable(trips.get(position.feed()).get(position.tripId())).map(trip -> vehicle(position, trip));
    }

    private LiveVehicle vehicle(VehiclePosition position, TripSummary trip) {
        return new LiveVehicle(position.tripId(), position.licensePlate(), trip.lineNumber(), trip.headsign(),
                trip.shapeId(), position.latitude(), position.longitude(), position.bearing(), position.timestamp());
    }

    private record Snapshot(List<LiveVehicle> vehicles, Instant takenAt) {}
}
