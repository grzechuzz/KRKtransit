package pl.krktransit.livevehicles.domain;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Objects;

public record TripForecast(String tripId, Instant fetchedAt, List<StopForecast> stops) implements LiveObservation {
    public TripForecast {
        Objects.requireNonNull(tripId, "Trip ID is required");
        Objects.requireNonNull(fetchedAt, "Fetch time is required");
        stops = List.copyOf(stops);
    }

    public boolean retainedAt(Instant now, Duration retention) { return now.isBefore(fetchedAt.plus(retention)); }

    public boolean olderThan(TripForecast other) { return fetchedAt.isBefore(other.fetchedAt); }
}
