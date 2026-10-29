package pl.krktransit.livevehicles.domain;

import java.time.Duration;
import java.time.Instant;

public record VehiclePosition(SourceId source, String tripId, String vehicleId, String licensePlate,
                              double latitude, double longitude, Double bearing,
                              Instant timestamp, Instant fetchedAt, StreamId streamId) {
    private static final Duration TOLERATED_CLOCK_SKEW = Duration.ofMinutes(1);

    public VehiclePosition {
        requireIdentity(source, vehicleId, licensePlate);
        requireLocation(latitude, longitude, bearing);
        requireObservationMetadata(fetchedAt, streamId);
    }

    public VehicleKey key() {
        if (hasLicensePlate()) return new VehicleKey(source, "plate", licensePlate);
        return new VehicleKey(source, "id", vehicleId);
    }

    public Instant observedAt() {
        if (hasUsableProviderTime()) return timestamp;
        return fetchedAt;
    }

    public boolean retainedAt(Instant now, Duration retention) { return now.isBefore(observedAt().plus(retention)); }

    public boolean newerThan(VehiclePosition previous) {
        int observed = observedAt().compareTo(previous.observedAt());
        if (observed != 0) return observed > 0;
        return newerByFetchOrStream(previous);
    }

    public static boolean validBearing(double value) { return withinRange(value, 0, 360); }

    private boolean newerByFetchOrStream(VehiclePosition previous) {
        int fetch = fetchedAt.compareTo(previous.fetchedAt);
        if (fetch != 0) return fetch > 0;
        return streamId.compareTo(previous.streamId) > 0;
    }

    private boolean hasLicensePlate() { return !blank(licensePlate); }

    private boolean hasUsableProviderTime() {
        return timestamp != null && !timestamp.isAfter(fetchedAt.plus(TOLERATED_CLOCK_SKEW));
    }

    private static void requireIdentity(SourceId source, String vehicleId, String licensePlate) {
        if (!validIdentity(source, vehicleId, licensePlate)) {
            throw new IllegalArgumentException("Source and vehicle identity are required");
        }
    }

    private static boolean validIdentity(SourceId source, String vehicleId, String licensePlate) {
        return source != null && (!blank(vehicleId) || !blank(licensePlate));
    }

    private static void requireLocation(double latitude, double longitude, Double bearing) {
        if (!validLocation(latitude, longitude, bearing)) {
            throw new IllegalArgumentException("Invalid vehicle coordinates or bearing");
        }
    }

    private static boolean validLocation(double latitude, double longitude, Double bearing) {
        return validCoordinates(latitude, longitude) && validOptionalBearing(bearing);
    }

    private static boolean validCoordinates(double latitude, double longitude) {
        return withinRange(latitude, -90, 90) && withinRange(longitude, -180, 180);
    }

    private static boolean validOptionalBearing(Double value) { return value == null || validBearing(value); }

    private static boolean withinRange(double value, double minimum, double maximum) {
        return Double.isFinite(value) && value >= minimum && value <= maximum;
    }

    private static void requireObservationMetadata(Instant fetchedAt, StreamId streamId) {
        if (!hasObservationMetadata(fetchedAt, streamId)) {
            throw new IllegalArgumentException("Fetch time and stream ID are required");
        }
    }

    private static boolean hasObservationMetadata(Instant fetchedAt, StreamId streamId) {
        return fetchedAt != null && streamId != null;
    }

    private static boolean blank(String value) { return value == null || value.isBlank(); }

    public record VehicleKey(SourceId source, String kind, String identity) {}
}
