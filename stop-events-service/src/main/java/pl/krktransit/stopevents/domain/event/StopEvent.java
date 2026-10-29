package pl.krktransit.stopevents.domain.event;

import java.time.Instant;
import java.time.LocalDate;
import pl.krktransit.stopevents.domain.source.SourceId;
import pl.krktransit.stopevents.domain.state.SavedStop;

public record StopEvent(SourceId source, String tripId, LocalDate serviceDate, int sequence, String stopId,
                        String lineNumber, String stopName, String stopDescription, int directionId,
                        String headsign, Instant plannedTime, Instant eventTime, int delaySeconds,
                        String vehicleId, String licensePlate, DetectionMethod method, boolean estimated,
                        String staticHash, int maxSequence) {
    public StopEvent {
        if (!present(headsign) || !present(licensePlate)) {
            throw new IllegalArgumentException("A stop event requires a headsign and license plate");
        }
    }

    private static boolean present(String value) { return value != null && !value.isBlank(); }
    public EventKey key() { return new EventKey(tripId, serviceDate, sequence); }
    public SavedStop savedStop() { return new SavedStop(delaySeconds, eventTime); }
}
