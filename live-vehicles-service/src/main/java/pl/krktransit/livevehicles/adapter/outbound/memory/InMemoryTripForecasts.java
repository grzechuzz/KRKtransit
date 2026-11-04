package pl.krktransit.livevehicles.adapter.outbound.memory;

import java.time.Duration;
import java.time.Instant;
import java.util.Comparator;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import pl.krktransit.livevehicles.application.TripForecasts;
import pl.krktransit.livevehicles.domain.TripForecast;

public final class InMemoryTripForecasts implements TripForecasts {
    private final Map<String, TripForecast> forecasts = new HashMap<>();
    private final Duration retention;
    private final int maximum;

    public InMemoryTripForecasts(Duration retention, int maximum) {
        requireValidLimits(retention, maximum);
        this.retention = retention;
        this.maximum = maximum;
    }

    @Override
    public synchronized void accept(TripForecast forecast, Instant now) {
        if (!forecast.retainedAt(now, retention)) return;
        var previous = forecasts.get(forecast.tripId());
        if (outdated(forecast, previous)) return;
        if (needsRoom(previous)) makeRoom(now);
        forecasts.put(forecast.tripId(), forecast);
    }

    @Override
    public synchronized Optional<TripForecast> find(String tripId, Instant now) {
        return Optional.ofNullable(forecasts.get(tripId)).filter(forecast -> forecast.retainedAt(now, retention));
    }

    private boolean outdated(TripForecast forecast, TripForecast previous) {
        return previous != null && forecast.olderThan(previous);
    }

    private boolean needsRoom(TripForecast previous) { return previous == null && atCapacity(); }

    private void makeRoom(Instant now) {
        forecasts.values().removeIf(forecast -> !forecast.retainedAt(now, retention));
        if (atCapacity()) evictOldest();
    }

    private void evictOldest() {
        forecasts.values().stream().min(Comparator.comparing(TripForecast::fetchedAt))
                .ifPresent(oldest -> forecasts.remove(oldest.tripId()));
    }

    private boolean atCapacity() { return forecasts.size() >= maximum; }

    private static void requireValidLimits(Duration retention, int maximum) {
        if (!validLimits(retention, maximum)) {
            throw new IllegalArgumentException("Forecast retention and capacity must be positive");
        }
    }

    private static boolean validLimits(Duration retention, int maximum) {
        return retention != null && retention.isPositive() && maximum > 0;
    }
}
