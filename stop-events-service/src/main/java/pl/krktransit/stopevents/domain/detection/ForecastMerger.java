package pl.krktransit.stopevents.domain.detection;

import java.util.HashMap;
import pl.krktransit.stopevents.domain.observation.ForecastUpdate;
import pl.krktransit.stopevents.domain.schedule.TripContext;
import pl.krktransit.stopevents.domain.state.RetainedForecast;
import pl.krktransit.stopevents.domain.state.TripForecasts;

public final class ForecastMerger {
    private final StopSequenceResolver resolver = new StopSequenceResolver();

    public TripForecasts merge(ForecastUpdate update, TripContext trip, TripForecasts retained) {
        Integer previousMinimum = retained == null ? null : retained.lastMinSequence();
        var resolved = resolver.resolve(update.stops(), trip, previousMinimum);
        Integer minimum = resolved.stream().map(StopSequenceResolver.ResolvedForecast::sequence)
                .min(Integer::compare).orElse(previousMinimum);
        boolean moved = previousMinimum != null && minimum != null && minimum > previousMinimum;
        var stops = new HashMap<Integer, RetainedForecast>();
        if (retained != null) stops.putAll(retained.stops());
        for (var forecast : resolved) mergeStop(stops, forecast, moved);
        return new TripForecasts(stops, minimum, update.fetchedAt());
    }

    private void mergeStop(HashMap<Integer, RetainedForecast> stops,
                           StopSequenceResolver.ResolvedForecast resolved, boolean moved) {
        var previous = stops.get(resolved.sequence());
        var forecast = resolved.forecast();
        var first = previous != null && !moved ? previous.firstSeenArrival() : forecast.time();
        stops.put(resolved.sequence(), new RetainedForecast(forecast.stopId(), first, forecast.time()));
    }
}
