package pl.krktransit.livevehicles.domain;

import java.time.Instant;
import java.util.Objects;

public record StopForecast(Integer stopSequence, String stopId, Instant arrivalTime) {
    public StopForecast {
        Objects.requireNonNull(arrivalTime, "Arrival time is required");
        requireIdentity(stopSequence, stopId);
    }

    private static void requireIdentity(Integer stopSequence, String stopId) {
        if (!hasIdentity(stopSequence, stopId)) throw new IllegalArgumentException("Stop identity is required");
    }

    private static boolean hasIdentity(Integer stopSequence, String stopId) { return stopSequence != null || stopId != null; }
}
