package pl.krktransit.livevehicles.application;

import java.time.Instant;
import java.util.Optional;
import pl.krktransit.livevehicles.domain.TripForecast;

public interface TripForecasts {
    void accept(TripForecast forecast, Instant now);
    Optional<TripForecast> find(String tripId, Instant now);
}
