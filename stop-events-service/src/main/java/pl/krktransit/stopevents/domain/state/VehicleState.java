package pl.krktransit.stopevents.domain.state;

import java.time.Duration;
import java.time.Instant;

public record VehicleState(String tripId, String licensePlate, int sequence, Instant timestamp, Instant fetchedAt) {
    public boolean expired(Instant observationTime, Duration inactivity) {
        return fetchedAt.plus(inactivity).isBefore(observationTime);
    }

    public boolean switchesTo(String nextTrip) { return !tripId.equals(nextTrip); }
}
