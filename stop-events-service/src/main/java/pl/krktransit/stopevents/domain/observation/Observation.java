package pl.krktransit.stopevents.domain.observation;

import java.time.Instant;
import pl.krktransit.stopevents.domain.source.SourceId;

public sealed interface Observation permits VehicleObservation, ForecastUpdate {
    SourceId source();
    String tripId();
    Instant fetchedAt();
}
