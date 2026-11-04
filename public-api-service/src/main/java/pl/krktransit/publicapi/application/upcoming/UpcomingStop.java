package pl.krktransit.publicapi.application.upcoming;

import java.time.Instant;
import pl.krktransit.publicapi.application.schedule.TripStop;

public record UpcomingStop(TripStop stop, Instant scheduledArrival, Instant predictedArrival, ArrivalSource predictionSource) {
    static UpcomingStop withoutPrediction(TripStop stop, Instant scheduledArrival) {
        return new UpcomingStop(stop, scheduledArrival, null, null);
    }
}
