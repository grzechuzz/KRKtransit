package pl.krktransit.stopevents.domain.detection;

import java.time.Instant;
import java.time.LocalDate;
import java.util.Optional;
import pl.krktransit.stopevents.domain.event.DetectionMethod;
import pl.krktransit.stopevents.domain.event.StopEvent;
import pl.krktransit.stopevents.domain.schedule.ScheduledStop;
import pl.krktransit.stopevents.domain.schedule.TripContext;
import pl.krktransit.stopevents.domain.source.SourceId;
import pl.krktransit.stopevents.domain.time.ServiceCalendar;

public final class EventFactory {
    private final ServiceCalendar calendar = new ServiceCalendar();

    public Optional<StopEvent> create(SourceId source, TripContext trip, ScheduledStop stop, LocalDate date,
                                      Instant time, String vehicleId, String plate, DetectionMethod method) {
        if (stop == null || !complete(trip, plate)) return Optional.empty();
        var planned = calendar.plannedTime(date, stop.arrivalSeconds());
        return Optional.of(new StopEvent(source, trip.tripId(), date, stop.sequence(), stop.stopId(),
                trip.lineNumber(), stop.name(), stop.description(), trip.directionId(), trip.headsign(), planned,
                time, calendar.delay(time, planned), vehicleId, plate, method, method != DetectionMethod.STOPPED_AT,
                trip.hash(), trip.maxSequence()));
    }

    public boolean complete(TripContext trip, String plate) {
        return trip.directionId() != null && present(trip.headsign()) && present(plate);
    }

    private boolean present(String value) { return value != null && !value.isBlank(); }
}
