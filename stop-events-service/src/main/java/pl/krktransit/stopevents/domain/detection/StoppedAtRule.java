package pl.krktransit.stopevents.domain.detection;

import java.time.LocalDate;
import java.util.List;
import pl.krktransit.stopevents.domain.event.DetectionMethod;
import pl.krktransit.stopevents.domain.event.StopEvent;
import pl.krktransit.stopevents.domain.observation.VehicleObservation;
import pl.krktransit.stopevents.domain.schedule.TripContext;
import pl.krktransit.stopevents.domain.state.SavedStops;

public final class StoppedAtRule {
    private final EventFactory factory = new EventFactory();

    public List<StopEvent> detect(VehicleObservation vehicle, TripContext trip, LocalDate date, SavedStops saved) {
        if (!vehicle.stopped() || saved.contains(trip.tripId(), date, vehicle.sequence())) {
            return List.of();
        }
        return factory.create(vehicle.source(), trip, trip.stop(vehicle.sequence()), date, vehicle.timestamp(),
                vehicle.vehicleId(), vehicle.licensePlate(), DetectionMethod.STOPPED_AT).stream().toList();
    }
}
