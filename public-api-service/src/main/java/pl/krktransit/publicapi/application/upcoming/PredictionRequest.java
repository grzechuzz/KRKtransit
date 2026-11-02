package pl.krktransit.publicapi.application.upcoming;

import java.time.LocalDate;
import java.util.List;
import pl.krktransit.publicapi.application.schedule.TripStop;

public record PredictionRequest(String cityId, String tripId, LocalDate serviceDate, String lineNumber,
                                List<TripStop> stops, List<Integer> targetSequences) {
    public PredictionRequest {
        stops = List.copyOf(stops);
        targetSequences = List.copyOf(targetSequences);
    }

    public int maximumSequence() { return stops.getLast().sequence(); }
}
