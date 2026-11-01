package pl.krktransit.stopevents.domain.lineevents;

import java.time.Instant;
import java.time.LocalDate;
import pl.krktransit.stopevents.domain.event.DetectionMethod;

public record LineEvent(long id, String sourceId, String tripId, LocalDate serviceDate, int stopSequence,
                        int maxStopSequence, String stopId, String stopName, String stopDescription,
                        Instant plannedTime, Instant eventTime, int delaySeconds, DetectionMethod method,
                        boolean estimated, Instant createdAt, String headsign) {}
