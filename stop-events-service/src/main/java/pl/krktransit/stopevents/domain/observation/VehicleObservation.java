package pl.krktransit.stopevents.domain.observation;

import java.time.Instant;
import pl.krktransit.stopevents.domain.source.SourceId;

public record VehicleObservation(SourceId source, Instant fetchedAt, String tripId, String licensePlate,
                                 String vehicleId, Integer sequence, Integer status, Instant timestamp)
        implements Observation {
    public boolean eligible() {
        return present(tripId) && present(licensePlate) && sequence != null && timestamp != null;
    }

    public boolean stopped() { return Integer.valueOf(1).equals(status); }

    private static boolean present(String value) { return value != null && !value.isBlank(); }
}
