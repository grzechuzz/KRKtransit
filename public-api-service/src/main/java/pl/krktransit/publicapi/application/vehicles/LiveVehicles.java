package pl.krktransit.publicapi.application.vehicles;

import static java.util.stream.Collectors.groupingBy;
import static java.util.stream.Collectors.mapping;
import static java.util.stream.Collectors.toMap;
import static java.util.stream.Collectors.toSet;

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
    private final PositionSnapshot positions;
    private final ScheduleSource schedule;
    private Enriched enriched;

    public LiveVehicles(PositionSnapshot positions, ScheduleSource schedule) {
        this.positions = positions;
        this.schedule = schedule;
    }

    public synchronized List<LiveVehicle> current() {
        var observed = positions.current();
        if (!isEnrichmentOf(enriched, observed)) enriched = new Enriched(observed, enrich(observed.vehicles()));
        return enriched.vehicles();
    }

    private static boolean isEnrichmentOf(Enriched candidate, PositionSnapshot.Positions observed) {
        return candidate != null && candidate.source() == observed;
    }

    private List<LiveVehicle> enrich(List<VehiclePosition> observed) {
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

    private record Enriched(PositionSnapshot.Positions source, List<LiveVehicle> vehicles) {}
}
