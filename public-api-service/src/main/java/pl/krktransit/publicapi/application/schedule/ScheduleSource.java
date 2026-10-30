package pl.krktransit.publicapi.application.schedule;

import java.util.Optional;

public interface ScheduleSource {
    Optional<String> currentHash(FeedId feed);

    Optional<TripStops> tripStops(String hash, String tripId);

    Optional<Shape> shape(String hash, String shapeId);
}
