package pl.krktransit.realtime.ingestion.domain.observation;

public sealed interface RealtimeObservation permits VehicleObservation, TripUpdateObservation {
    ObservationOrigin origin();
}
