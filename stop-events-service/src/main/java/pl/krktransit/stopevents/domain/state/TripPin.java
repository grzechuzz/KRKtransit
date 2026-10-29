package pl.krktransit.stopevents.domain.state;

import java.time.Duration;
import java.time.Instant;

public record TripPin(String hash, Instant fetchedAt) {
    public boolean expired(Instant observationTime, Duration inactivity) {
        return fetchedAt.plus(inactivity).isBefore(observationTime);
    }

    public TripPin observedAt(Instant observationTime) {
        return new TripPin(hash, fetchedAt.isAfter(observationTime) ? fetchedAt : observationTime);
    }
}
