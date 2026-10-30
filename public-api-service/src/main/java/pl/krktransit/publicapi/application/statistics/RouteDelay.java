package pl.krktransit.publicapi.application.statistics;

import java.time.Instant;
import java.time.LocalDate;

public record RouteDelay(String tripId, LocalDate serviceDate, String lineNumber, String vehicleNumber,
                         String firstStop, String lastStop, Instant firstPlannedTime, Instant firstEventTime,
                         Instant lastPlannedTime, Instant lastEventTime, int startDelaySeconds, int endDelaySeconds,
                         int delayGeneratedSeconds, String headsign, boolean estimated) {}
