package pl.krktransit.stopevents.domain.observation;

import java.time.Instant;
import java.util.List;
import pl.krktransit.stopevents.domain.source.SourceId;

public record ForecastUpdate(SourceId source, Instant fetchedAt, String tripId, List<StopForecast> stops)
        implements Observation {
    public ForecastUpdate { stops = List.copyOf(stops); }

    public boolean eligible() {
        return tripId != null && !tripId.isBlank() && stops.stream().anyMatch(StopForecast::usable);
    }
}
