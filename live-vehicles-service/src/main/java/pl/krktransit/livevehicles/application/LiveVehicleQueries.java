package pl.krktransit.livevehicles.application;

import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import pl.krktransit.livevehicles.domain.TripForecast;
import pl.krktransit.livevehicles.domain.VehiclePosition;

public final class LiveVehicleQueries {
    private final VehiclePositions positions;
    private final TripForecasts forecasts;
    private final Clock clock;

    public LiveVehicleQueries(VehiclePositions positions, TripForecasts forecasts, Clock clock) {
        this.positions = positions;
        this.forecasts = forecasts;
        this.clock = clock;
    }

    public Snapshot list() {
        var now = clock.instant();
        return new Snapshot(now, positions.snapshot(now));
    }

    public Optional<TripForecast> forecast(String tripId) { return forecasts.find(tripId, clock.instant()); }

    public record Snapshot(Instant generatedAt, List<VehiclePosition> vehicles) {
        public Snapshot { vehicles = List.copyOf(vehicles); }
    }
}
