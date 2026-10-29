package pl.krktransit.livevehicles.domain;

import java.time.Duration;
import java.time.Instant;

public record VehiclePosition(SourceId source, String tripId, String vehicleId, String licensePlate,
                              double latitude, double longitude, Double bearing,
                              Instant timestamp, Instant fetchedAt, StreamId streamId) {
    private static final Duration TOLERATED_CLOCK_SKEW = Duration.ofMinutes(1);

    public VehiclePosition {
        if (source == null || (blank(vehicleId) && blank(licensePlate))) {
            throw new IllegalArgumentException("Source and vehicle identity are required");
        }
        if (!coordinates(latitude, longitude) || !bearing(bearing)) {
            throw new IllegalArgumentException("Invalid vehicle coordinates or bearing");
        }
        if (fetchedAt == null || streamId == null) {
            throw new IllegalArgumentException("Fetch time and stream ID are required");
        }
    }

    public VehicleKey key() {
        return blank(licensePlate) ? new VehicleKey(source, "id", vehicleId) : new VehicleKey(source, "plate", licensePlate);
    }

    public Instant observedAt() {
        return timestamp == null || timestamp.isAfter(fetchedAt.plus(TOLERATED_CLOCK_SKEW)) ? fetchedAt : timestamp;
    }

    public boolean retainedAt(Instant now, Duration retention) { return now.isBefore(observedAt().plus(retention)); }

    public boolean newerThan(VehiclePosition previous) {
        int observed = observedAt().compareTo(previous.observedAt());
        if (observed != 0) return observed > 0;
        int fetch = fetchedAt.compareTo(previous.fetchedAt);
        return fetch > 0 || (fetch == 0 && streamId.compareTo(previous.streamId) > 0);
    }

    public static boolean validBearing(double value) { return Double.isFinite(value) && value >= 0 && value <= 360; }

    private static boolean coordinates(double latitude, double longitude) {
        return Double.isFinite(latitude) && Double.isFinite(longitude)
                && latitude >= -90 && latitude <= 90 && longitude >= -180 && longitude <= 180;
    }

    private static boolean bearing(Double value) { return value == null || validBearing(value); }

    private static boolean blank(String value) { return value == null || value.isBlank(); }

    public record VehicleKey(SourceId source, String kind, String identity) {}
}
