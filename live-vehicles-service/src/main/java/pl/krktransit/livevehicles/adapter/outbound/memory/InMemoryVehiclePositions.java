package pl.krktransit.livevehicles.adapter.outbound.memory;

import java.time.Duration;
import java.time.Instant;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import pl.krktransit.livevehicles.application.VehiclePositions;
import pl.krktransit.livevehicles.domain.VehiclePosition;
import pl.krktransit.livevehicles.domain.VehiclePosition.VehicleKey;

public final class InMemoryVehiclePositions implements VehiclePositions {
    private final Map<VehicleKey, VehiclePosition> positions = new HashMap<>();
    private final Duration retention;
    private final int maximum;

    public InMemoryVehiclePositions(Duration retention, int maximum) {
        if (retention == null || !retention.isPositive() || maximum < 1) {
            throw new IllegalArgumentException("Position retention and capacity must be positive");
        }
        this.retention = retention;
        this.maximum = maximum;
    }

    @Override
    public synchronized void accept(VehiclePosition position, Instant now) {
        if (!position.retainedAt(now, retention)) return;
        var previous = positions.get(position.key());
        if (previous != null && !position.newerThan(previous)) return;
        if (previous == null && positions.size() >= maximum) makeRoom(now);
        positions.put(position.key(), position);
    }

    @Override
    public synchronized List<VehiclePosition> snapshot(Instant now) {
        expire(now);
        return positions.values().stream().sorted(Comparator
                .comparing((VehiclePosition position) -> position.source().toString())
                .thenComparing(position -> position.key().kind()).thenComparing(position -> position.key().identity())).toList();
    }

    private void makeRoom(Instant now) {
        expire(now);
        if (positions.size() < maximum) return;
        positions.values().stream().min(Comparator.comparing(VehiclePosition::observedAt))
                .ifPresent(oldest -> positions.remove(oldest.key()));
    }

    private void expire(Instant now) { positions.values().removeIf(position -> !position.retainedAt(now, retention)); }
}
