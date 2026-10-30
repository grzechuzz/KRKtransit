package pl.krktransit.publicapi.application.vehicles;

import java.util.List;

public interface VehiclePositionsSource {
    List<VehiclePosition> positions();
}
