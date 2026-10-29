package pl.krktransit.stopevents.domain.detection;

import java.util.List;
import pl.krktransit.stopevents.domain.event.StopEvent;
import pl.krktransit.stopevents.domain.state.VehicleState;

public record DetectionOutcome(List<StopEvent> events, VehicleState vehicleState) {
    public DetectionOutcome { events = List.copyOf(events); }
}
