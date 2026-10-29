package pl.krktransit.stopevents.domain.state;

import java.time.Duration;
import java.time.Instant;
import java.util.Map;

public record TripForecasts(Map<Integer, RetainedForecast> stops, Integer lastMinSequence, Instant fetchedAt) {
    public TripForecasts { stops = Map.copyOf(stops); }

    public boolean expired(Instant observationTime, Duration inactivity) {
        return fetchedAt.plus(inactivity).isBefore(observationTime);
    }
}
