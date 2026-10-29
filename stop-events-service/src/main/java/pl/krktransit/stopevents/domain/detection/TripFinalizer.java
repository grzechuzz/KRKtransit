package pl.krktransit.stopevents.domain.detection;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import pl.krktransit.stopevents.domain.event.DetectionMethod;
import pl.krktransit.stopevents.domain.event.StopEvent;
import pl.krktransit.stopevents.domain.schedule.TripContext;
import pl.krktransit.stopevents.domain.source.SourceId;
import pl.krktransit.stopevents.domain.state.SavedStops;
import pl.krktransit.stopevents.domain.state.TripForecasts;
import pl.krktransit.stopevents.domain.state.VehicleState;
import pl.krktransit.stopevents.domain.time.ServiceCalendar;

public final class TripFinalizer {
    private final ServiceCalendar calendar = new ServiceCalendar();
    private final EventFactory factory = new EventFactory();
    private final EventValidator validator = new EventValidator();

    public List<StopEvent> finalizeTrip(SourceId source, VehicleState previous, TripContext trip,
                                        TripForecasts forecasts, SavedStops saved, Instant observedAt) {
        if (trip == null || forecasts == null || !departed(previous)) return List.of();
        var events = new ArrayList<StopEvent>();
        for (var stop : trip.between(previous.sequence(), trip.maxSequence())) {
            var forecast = forecasts.stops().get(stop.sequence());
            var date = calendar.serviceDate(previous.timestamp(), stop.arrivalSeconds());
            if (forecast == null || saved.contains(trip.tripId(), date, stop.sequence())) continue;
            boolean last = stop.sequence() == trip.maxSequence();
            var time = last ? forecast.firstSeenArrival() : forecast.lastSeenArrival();
            if (time.isAfter(observedAt)) continue;
            var method = last ? DetectionMethod.TIMEOUT : DetectionMethod.SEQ_JUMP;
            factory.create(source, trip, stop, date, time, null, previous.licensePlate(), method).ifPresent(events::add);
        }
        return validator.validate(events, saved);
    }

    private boolean departed(VehicleState previous) { return previous.sequence() > 1; }
}
