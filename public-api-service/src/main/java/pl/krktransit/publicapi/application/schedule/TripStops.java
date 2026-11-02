package pl.krktransit.publicapi.application.schedule;

import java.util.List;

public record TripStops(String tripId, String lineNumber, List<TripStop> stops) {
    public TripStops { stops = List.copyOf(stops); }

    public boolean hasStops() { return !stops.isEmpty(); }
}
