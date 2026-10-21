package pl.krktransit.schedule.application.querying;

import java.util.List;
import pl.krktransit.schedule.domain.schedule.ScheduleHash;

public record TripContext(ScheduleHash hash, String tripId, String routeId, String lineNumber,
                          Integer directionId, String headsign, String shapeId,
                          int maxStopSequence, List<TripStop> stops) {
    public TripContext { stops = List.copyOf(stops); }
}
