package pl.krktransit.realtime.ingestion.domain.observation;

import java.util.Objects;

public record VehicleObservation(ObservationOrigin origin, String tripId, String vehicleId, String licensePlate,
                                 VehicleLocation location, String stopId, Integer currentStopSequence,
                                 Integer currentStatus, Long timestamp) implements RealtimeObservation {
    public VehicleObservation {
        Objects.requireNonNull(origin, "origin");
    }
}
