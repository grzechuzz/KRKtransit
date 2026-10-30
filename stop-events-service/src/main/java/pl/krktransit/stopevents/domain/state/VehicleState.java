package pl.krktransit.stopevents.domain.state;

import java.time.Duration;
import java.time.Instant;
import pl.krktransit.stopevents.domain.observation.VehicleObservation;

public record VehicleState(String tripId, String licensePlate, int sequence, Instant timestamp, Instant fetchedAt) {
    public boolean expired(Instant observationTime, Duration inactivity) {
        return fetchedAt.plus(inactivity).isBefore(observationTime);
    }

    public boolean switchesTo(String nextTrip) { return !tripId.equals(nextTrip); }

    public boolean canAdvanceTo(VehicleObservation observation) {
        int time = observation.timestamp().compareTo(timestamp);
        if (time != 0) return time > 0;
        return !switchesTo(observation.tripId()) && observation.sequence() >= sequence
                && !observation.fetchedAt().isBefore(fetchedAt);
    }
}
