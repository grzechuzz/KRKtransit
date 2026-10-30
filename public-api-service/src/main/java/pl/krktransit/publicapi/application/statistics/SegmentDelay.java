package pl.krktransit.publicapi.application.statistics;

import java.time.Instant;
import java.time.LocalDate;

public record SegmentDelay(String tripId, LocalDate serviceDate, String lineNumber, String vehicleNumber,
                           String fromStop, String toStop, int fromSequence, int toSequence,
                           Instant fromPlannedTime, Instant fromEventTime, Instant toPlannedTime, Instant toEventTime,
                           int delayGeneratedSeconds, String headsign, boolean estimated) {}
