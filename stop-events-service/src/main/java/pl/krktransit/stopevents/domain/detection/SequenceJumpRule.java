package pl.krktransit.stopevents.domain.detection;

import java.util.ArrayList;
import java.util.List;
import pl.krktransit.stopevents.domain.event.DetectionMethod;
import pl.krktransit.stopevents.domain.event.StopEvent;
import pl.krktransit.stopevents.domain.observation.VehicleObservation;
import pl.krktransit.stopevents.domain.schedule.TripContext;
import pl.krktransit.stopevents.domain.state.SavedStops;
import pl.krktransit.stopevents.domain.state.TripForecasts;
import pl.krktransit.stopevents.domain.state.VehicleState;
import pl.krktransit.stopevents.domain.time.ServiceCalendar;

public final class SequenceJumpRule {
    private final EventFactory factory = new EventFactory();
    private final ServiceCalendar calendar = new ServiceCalendar();

    public List<StopEvent> detect(VehicleObservation vehicle, VehicleState previous, TripContext trip,
                                 TripForecasts forecasts, SavedStops saved) {
        if (!jumped(vehicle, previous) || forecasts == null) return List.of();
        var events = new ArrayList<StopEvent>();
        for (var stop : trip.between(previous.sequence(), vehicle.sequence() - 1)) {
            var forecast = forecasts.stops().get(stop.sequence());
            var date = calendar.serviceDate(vehicle.timestamp(), stop.arrivalSeconds());
            if (forecast == null || saved.contains(trip.tripId(), date, stop.sequence())) continue;
            if (forecast.lastSeenArrival().isAfter(vehicle.fetchedAt())) continue;
            factory.create(vehicle.source(), trip, stop, date, forecast.lastSeenArrival(), vehicle.vehicleId(),
                    vehicle.licensePlate(), DetectionMethod.SEQ_JUMP).ifPresent(events::add);
        }
        return List.copyOf(events);
    }

    private boolean jumped(VehicleObservation vehicle, VehicleState previous) {
        return previous != null && !previous.switchesTo(vehicle.tripId()) && vehicle.sequence() > previous.sequence();
    }
}
