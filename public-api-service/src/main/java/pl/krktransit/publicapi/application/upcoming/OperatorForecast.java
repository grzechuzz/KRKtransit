package pl.krktransit.publicapi.application.upcoming;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import pl.krktransit.publicapi.application.schedule.TripStop;

public record OperatorForecast(List<ForecastedStop> stops) {
    public static final OperatorForecast NONE = new OperatorForecast(List.of());

    public OperatorForecast { stops = List.copyOf(stops); }

    public Optional<Instant> arrivalAt(TripStop stop, List<TripStop> tripStops) {
        return bySequence(stop).or(() -> byStopId(stop, tripStops)).map(ForecastedStop::arrival);
    }

    private Optional<ForecastedStop> bySequence(TripStop stop) {
        return stops.stream().filter(forecasted -> forecasted.describes(stop)).findFirst();
    }

    private Optional<ForecastedStop> byStopId(TripStop stop, List<TripStop> tripStops) {
        if (!appearsOnce(stop, tripStops)) return Optional.empty();
        var candidates = stops.stream().filter(forecasted -> stop.stopId().equals(forecasted.stopId())).toList();
        return candidates.size() == 1 ? Optional.of(candidates.getFirst()) : Optional.empty();
    }

    private static boolean appearsOnce(TripStop stop, List<TripStop> tripStops) {
        return tripStops.stream().filter(other -> other.stopId().equals(stop.stopId())).count() == 1;
    }

    public record ForecastedStop(Integer sequence, String stopId, Instant arrival) {
        private boolean describes(TripStop stop) { return sameSequence(stop) && compatibleStop(stop); }

        private boolean sameSequence(TripStop stop) { return sequence != null && sequence == stop.sequence(); }

        private boolean compatibleStop(TripStop stop) { return stopId == null || stopId.equals(stop.stopId()); }
    }
}
