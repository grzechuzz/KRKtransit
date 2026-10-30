package pl.krktransit.publicapi.application.upcoming;

import java.time.Instant;
import java.util.List;

public record UpcomingTrip(String tripId, TripProgress progress, Instant observedAt, List<UpcomingStop> stops) {
    public UpcomingTrip { stops = List.copyOf(stops); }

    static UpcomingTrip withoutStops(String tripId, TripProgress progress, Instant observedAt) {
        return new UpcomingTrip(tripId, progress, observedAt, List.of());
    }
}
