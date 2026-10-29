package pl.krktransit.stopevents.domain.state;

import java.time.Instant;

public record RetainedForecast(String stopId, Instant firstSeenArrival, Instant lastSeenArrival) {}
