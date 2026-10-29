package pl.krktransit.livevehicles.application;

import java.time.Instant;
import java.util.List;
import pl.krktransit.livevehicles.domain.VehiclePosition;

public interface VehiclePositions {
    void accept(VehiclePosition position, Instant now);
    List<VehiclePosition> snapshot(Instant now);
}
