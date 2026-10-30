package pl.krktransit.publicapi.application.schedule;

import java.util.Optional;
import pl.krktransit.publicapi.application.ApiFailure;

public final class ScheduleLookups {
    private final ScheduleSource schedule;

    public ScheduleLookups(ScheduleSource schedule) { this.schedule = schedule; }

    public TripStops tripStops(String tripId) {
        return currentHashFor(tripId).flatMap(hash -> schedule.tripStops(hash, tripId)).filter(TripStops::hasStops)
                .orElseThrow(() -> new ApiFailure.NotFound("Trip", tripId));
    }

    public Shape shape(String shapeId) {
        return currentHashFor(shapeId).flatMap(hash -> schedule.shape(hash, shapeId)).filter(Shape::hasPoints)
                .orElseThrow(() -> new ApiFailure.NotFound("Shape", shapeId));
    }

    private Optional<String> currentHashFor(String scopedId) {
        return FeedId.ofScopedId(scopedId).flatMap(schedule::currentHash);
    }
}
