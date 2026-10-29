package pl.krktransit.livevehicles.application;

import java.time.Clock;
import java.time.Instant;
import java.util.List;
import pl.krktransit.livevehicles.domain.VehiclePosition;

public final class LiveVehicleQueries {
    private final VehiclePositions positions;
    private final Clock clock;

    public LiveVehicleQueries(VehiclePositions positions, Clock clock) {
        this.positions = positions;
        this.clock = clock;
    }

    public Snapshot list() {
        var now = clock.instant();
        return new Snapshot(now, positions.snapshot(now));
    }

    public record Snapshot(Instant generatedAt, List<VehiclePosition> vehicles) {
        public Snapshot { vehicles = List.copyOf(vehicles); }
    }
}
