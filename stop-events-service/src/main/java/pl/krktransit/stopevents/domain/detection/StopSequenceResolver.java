package pl.krktransit.stopevents.domain.detection;

import java.util.ArrayList;
import java.util.List;
import pl.krktransit.stopevents.domain.observation.StopForecast;
import pl.krktransit.stopevents.domain.schedule.TripContext;

public final class StopSequenceResolver {
    public List<ResolvedForecast> resolve(List<StopForecast> updates, TripContext trip, Integer lastMinimum) {
        var resolved = new ArrayList<ResolvedForecast>();
        Integer previous = null;
        for (var update : updates) {
            if (!update.usable()) continue;
            Integer sequence = sequence(update, trip, previous, lastMinimum);
            if (sequence == null) continue;
            resolved.add(new ResolvedForecast(sequence, update));
            previous = sequence;
        }
        return List.copyOf(resolved);
    }

    private Integer sequence(StopForecast update, TripContext trip, Integer previous, Integer lastMinimum) {
        if (update.sequence() != null) return update.sequence();
        long lowerBound = previous != null ? (long) previous + 1 : initialBound(lastMinimum);
        return trip.occurrences(update.stopId()).stream().filter(value -> value >= lowerBound)
                .findFirst().orElse(null);
    }

    private int initialBound(Integer lastMinimum) { return lastMinimum == null ? 1 : lastMinimum; }

    public record ResolvedForecast(int sequence, StopForecast forecast) {}
}
