package pl.krktransit.publicapi.application.vehicles;

import java.time.Instant;
import java.util.Objects;
import pl.krktransit.publicapi.application.schedule.FeedId;

public record VehiclePosition(FeedId feed, String tripId, String licensePlate, double latitude, double longitude,
                              Double bearing, Instant timestamp, StopProgress progress) {
    public VehiclePosition {
        Objects.requireNonNull(feed);
        Objects.requireNonNull(tripId);
        Objects.requireNonNull(licensePlate);
        Objects.requireNonNull(timestamp);
        Objects.requireNonNull(progress);
    }

    public boolean newerThan(VehiclePosition other) { return timestamp.isAfter(other.timestamp); }
}
