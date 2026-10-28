package pl.krktransit.realtime.ingestion.domain.observation;

import java.util.List;
import java.util.Objects;

public record TripUpdateObservation(ObservationOrigin origin, String tripId, Integer tripScheduleRelationship,
                                    String vehicleId, Long timestamp, List<StopUpdate> stopUpdates)
        implements RealtimeObservation {
    public TripUpdateObservation {
        Objects.requireNonNull(origin, "origin");
        stopUpdates = List.copyOf(stopUpdates);
    }
}
