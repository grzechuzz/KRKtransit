package pl.krktransit.stopevents.domain.detection;

import java.util.ArrayList;
import java.util.List;
import pl.krktransit.stopevents.domain.event.StopEvent;
import pl.krktransit.stopevents.domain.observation.VehicleObservation;
import pl.krktransit.stopevents.domain.schedule.TripContext;
import pl.krktransit.stopevents.domain.state.SavedStops;
import pl.krktransit.stopevents.domain.state.TripForecasts;
import pl.krktransit.stopevents.domain.state.VehicleState;
import pl.krktransit.stopevents.domain.time.ServiceCalendar;

public final class VehicleDetector {
    private final ServiceCalendar calendar = new ServiceCalendar();
    private final StoppedAtRule stoppedAt = new StoppedAtRule();
    private final SequenceJumpRule sequenceJump = new SequenceJumpRule();
    private final EventValidator validator = new EventValidator();
    private final BatchValidator batchValidator = new BatchValidator();

    public DetectionOutcome detect(VehicleObservation vehicle, VehicleState previous, TripContext trip,
                                   TripForecasts forecasts, SavedStops saved) {
        if (!vehicle.eligible() || trip == null || trip.stop(vehicle.sequence()) == null) {
            return new DetectionOutcome(List.of(), null);
        }
        var date = calendar.serviceDate(vehicle.timestamp(), trip.stop(vehicle.sequence()).arrivalSeconds());
        var candidates = new ArrayList<StopEvent>(stoppedAt.detect(vehicle, trip, date, saved));
        candidates.addAll(sequenceJump.detect(vehicle, previous, trip, forecasts, saved));
        var accepted = validator.validate(candidates, saved);
        if (vehicle.stopped() && accepted.size() > 1) accepted = batchValidator.validate(accepted);
        return new DetectionOutcome(accepted, new VehicleState(vehicle.tripId(), vehicle.licensePlate(),
                vehicle.sequence(), vehicle.timestamp(), vehicle.fetchedAt()));
    }
}
