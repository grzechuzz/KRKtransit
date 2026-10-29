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
        requireValidLimits(retention, maximum);
        this.retention = retention;
        this.maximum = maximum;
    }

    @Override
    public synchronized void accept(VehiclePosition position, Instant now) {
        if (!position.retainedAt(now, retention)) return;
        var previous = positions.get(position.key());
        if (outdated(position, previous)) return;
        if (needsRoom(previous)) makeRoom(now);
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
        if (!atCapacity()) return;
        evictOldest();
    }

    private void evictOldest() {
        positions.values().stream().min(Comparator.comparing(VehiclePosition::observedAt))
                .ifPresent(oldest -> positions.remove(oldest.key()));
    }

    private boolean outdated(VehiclePosition position, VehiclePosition previous) {
        return previous != null && !position.newerThan(previous);
    }

    private boolean needsRoom(VehiclePosition previous) { return previous == null && atCapacity(); }

    private boolean atCapacity() { return positions.size() >= maximum; }

    private void expire(Instant now) { positions.values().removeIf(position -> !position.retainedAt(now, retention)); }

    private static void requireValidLimits(Duration retention, int maximum) {
        if (!validLimits(retention, maximum)) {
            throw new IllegalArgumentException("Position retention and capacity must be positive");
        }
    }

    private static boolean validLimits(Duration retention, int maximum) {
        return retention != null && retention.isPositive() && maximum > 0;
    }
}
