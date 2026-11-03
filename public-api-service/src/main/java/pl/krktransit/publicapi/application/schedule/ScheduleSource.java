package pl.krktransit.publicapi.application.schedule;

import java.util.Optional;
import java.util.Set;

public interface ScheduleSource {
    Optional<String> currentHash(FeedId feed);

    TripSummaries tripSummaries(String hash, Set<String> tripIds);

    Optional<TripStops> tripStops(String hash, String tripId);

    Optional<Shape> shape(String hash, String shapeId);
}
