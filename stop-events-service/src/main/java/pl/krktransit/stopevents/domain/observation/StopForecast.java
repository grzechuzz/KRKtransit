package pl.krktransit.stopevents.domain.observation;

import java.time.Instant;

public record StopForecast(Integer sequence, String stopId, Instant arrival, Instant departure) {
    public boolean usable() {
        return stopId != null && !stopId.isBlank() && time() != null;
    }

    public Instant time() { return arrival != null ? arrival : departure; }
}
