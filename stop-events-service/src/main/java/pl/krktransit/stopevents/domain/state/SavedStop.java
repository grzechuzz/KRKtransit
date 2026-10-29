package pl.krktransit.stopevents.domain.state;

import java.time.Instant;

public record SavedStop(int delaySeconds, Instant eventTime) {}
