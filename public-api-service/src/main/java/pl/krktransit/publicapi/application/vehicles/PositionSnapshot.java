package pl.krktransit.publicapi.application.vehicles;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

public final class PositionSnapshot {
    private final VehiclePositionsSource source;
    private final Duration lifetime;
    private final Clock clock;
    private Positions positions;

    public PositionSnapshot(VehiclePositionsSource source, Duration lifetime, Clock clock) {
        this.source = source;
        this.lifetime = lifetime;
        this.clock = clock;
    }

    public synchronized Positions current() {
        if (!isFresh(positions)) positions = new Positions(source.positions(), clock.instant());
        return positions;
    }

    public Optional<VehiclePosition> newestOnTrip(String tripId) {
        return current().vehicles().stream().filter(position -> position.tripId().equals(tripId))
                .reduce((first, second) -> second.newerThan(first) ? second : first);
    }

    private boolean isFresh(Positions candidate) {
        return candidate != null && clock.instant().isBefore(candidate.takenAt().plus(lifetime));
    }

    public record Positions(List<VehiclePosition> vehicles, Instant takenAt) {
        public Positions { vehicles = List.copyOf(vehicles); }
    }
}
