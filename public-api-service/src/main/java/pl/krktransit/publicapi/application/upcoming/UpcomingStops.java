package pl.krktransit.publicapi.application.upcoming;

import java.util.List;
import java.util.OptionalInt;
import java.util.stream.IntStream;
import pl.krktransit.publicapi.application.ApiFailure;
import pl.krktransit.publicapi.application.schedule.ScheduleLookups;
import pl.krktransit.publicapi.application.schedule.TripStop;
import pl.krktransit.publicapi.application.vehicles.PositionSnapshot;
import pl.krktransit.publicapi.application.vehicles.VehiclePosition;
import pl.krktransit.publicapi.application.vehicles.VehicleStopStatus;

public final class UpcomingStops {
    private static final int MAXIMUM_STOPS = 5;
    private final PositionSnapshot positions;
    private final ScheduleLookups schedule;
    private final ServiceCalendar calendar = new ServiceCalendar();

    public UpcomingStops(PositionSnapshot positions, ScheduleLookups schedule) {
        this.positions = positions;
        this.schedule = schedule;
    }

    public UpcomingTrip forTrip(String tripId) {
        var vehicle = positions.newestOnTrip(tripId).orElseThrow(() -> new ApiFailure.NotFound("Vehicle on trip", tripId));
        return upcoming(vehicle, schedule.tripStops(tripId).stops());
    }

    private UpcomingTrip upcoming(VehiclePosition vehicle, List<TripStop> stops) {
        var reference = referenceIndex(vehicle, stops);
        if (reference.isEmpty()) return UpcomingTrip.withoutStops(vehicle.tripId(), TripProgress.UNKNOWN, vehicle.timestamp());
        int first = firstUpcomingIndex(vehicle, reference.getAsInt());
        if (first >= stops.size()) return UpcomingTrip.withoutStops(vehicle.tripId(), TripProgress.FINISHED, vehicle.timestamp());
        var date = calendar.serviceDate(vehicle.timestamp(), stops.get(reference.getAsInt()).arrivalSeconds());
        var upcoming = stops.subList(first, Math.min(first + MAXIMUM_STOPS, stops.size())).stream()
                .map(stop -> new UpcomingStop(stop, calendar.plannedTime(date, stop.arrivalSeconds()))).toList();
        return new UpcomingTrip(vehicle.tripId(), TripProgress.IN_PROGRESS, vehicle.timestamp(), upcoming);
    }

    private OptionalInt referenceIndex(VehiclePosition vehicle, List<TripStop> stops) {
        var progress = vehicle.progress();
        if (progress.sequence() == null || progress.status() == VehicleStopStatus.UNRECOGNIZED) return OptionalInt.empty();
        return IntStream.range(0, stops.size()).filter(index -> stops.get(index).sequence() == progress.sequence()).findFirst();
    }

    private static int firstUpcomingIndex(VehiclePosition vehicle, int reference) {
        return vehicle.progress().status() == VehicleStopStatus.STOPPED_AT ? reference + 1 : reference;
    }
}
