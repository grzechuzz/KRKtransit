package pl.krktransit.publicapi.application.upcoming;

import java.time.LocalDate;
import java.util.Comparator;
import java.util.List;
import java.util.OptionalInt;
import java.util.stream.IntStream;
import pl.krktransit.publicapi.application.ApiFailure;
import pl.krktransit.publicapi.application.schedule.ScheduleLookups;
import pl.krktransit.publicapi.application.schedule.TripStop;
import pl.krktransit.publicapi.application.schedule.TripStops;
import pl.krktransit.publicapi.application.vehicles.PositionSnapshot;
import pl.krktransit.publicapi.application.vehicles.StopProgress;
import pl.krktransit.publicapi.application.vehicles.VehiclePosition;
import pl.krktransit.publicapi.application.vehicles.VehicleStopStatus;

public final class UpcomingStops {
    private static final int MAXIMUM_STOPS = 5;
    private final PositionSnapshot positions;
    private final ScheduleLookups schedule;
    private final PredictionSource predictions;
    private final ServiceCalendar calendar = new ServiceCalendar();

    public UpcomingStops(PositionSnapshot positions, ScheduleLookups schedule, PredictionSource predictions) {
        this.positions = positions;
        this.schedule = schedule;
        this.predictions = predictions;
    }

    public UpcomingTrip forTrip(String tripId) {
        var vehicle = positions.newestOnTrip(tripId).orElseThrow(() -> new ApiFailure.NotFound("Vehicle on trip", tripId));
        return upcoming(vehicle, schedule.tripStops(tripId));
    }

    private UpcomingTrip upcoming(VehiclePosition vehicle, TripStops trip) {
        var stops = trip.stops();
        var reference = referenceIndex(vehicle, stops);
        if (reference.isEmpty()) return UpcomingTrip.withoutStops(vehicle.tripId(), TripProgress.UNKNOWN, vehicle.timestamp());
        int first = firstUpcomingIndex(vehicle, reference.getAsInt());
        if (first >= stops.size()) return UpcomingTrip.withoutStops(vehicle.tripId(), TripProgress.FINISHED, vehicle.timestamp());
        var date = calendar.serviceDate(vehicle.timestamp(), stops.get(reference.getAsInt()).arrivalSeconds());
        var rows = stops.subList(first, Math.min(first + MAXIMUM_STOPS, stops.size()));
        var delays = predictions.predict(predictionRequest(vehicle, trip, date, rows));
        var upcoming = rows.stream().map(stop -> upcomingStop(stop, date, delays)).toList();
        return new UpcomingTrip(vehicle.tripId(), TripProgress.IN_PROGRESS, vehicle.timestamp(), upcoming);
    }

    private static PredictionRequest predictionRequest(VehiclePosition vehicle, TripStops trip, LocalDate date, List<TripStop> rows) {
        return new PredictionRequest(vehicle.feed().cityId(), vehicle.tripId(), date, trip.lineNumber(), trip.stops(),
                rows.stream().map(TripStop::sequence).toList());
    }

    private UpcomingStop upcomingStop(TripStop stop, LocalDate date, PredictedDelays delays) {
        var scheduled = calendar.plannedTime(date, stop.arrivalSeconds());
        var delay = delays.forSequence(stop.sequence());
        return new UpcomingStop(stop, scheduled, delay.isPresent() ? scheduled.plusSeconds(delay.getAsInt()) : null);
    }

    private OptionalInt referenceIndex(VehiclePosition vehicle, List<TripStop> stops) {
        var progress = vehicle.progress();
        if (progress.status() == VehicleStopStatus.UNRECOGNIZED) return OptionalInt.empty();
        if (progress.stopId() != null) return occurrenceOfStop(progress, stops);
        if (progress.sequence() == null) return OptionalInt.empty();
        return IntStream.range(0, stops.size()).filter(index -> stops.get(index).sequence() == progress.sequence()).findFirst();
    }

    private static OptionalInt occurrenceOfStop(StopProgress progress, List<TripStop> stops) {
        var occurrences = IntStream.range(0, stops.size()).filter(index -> stops.get(index).stopId().equals(progress.stopId()))
                .boxed().toList();
        if (occurrences.size() == 1) return OptionalInt.of(occurrences.getFirst());
        if (occurrences.isEmpty() || progress.sequence() == null) return OptionalInt.empty();
        Comparator<Integer> nearest = Comparator.comparingInt(index -> distance(stops.get(index), progress.sequence()));
        return occurrences.stream().min(nearest.thenComparing(index -> stops.get(index).sequence() < progress.sequence()))
                .map(OptionalInt::of).orElseThrow();
    }

    private static int distance(TripStop stop, int sequence) { return Math.abs(stop.sequence() - sequence); }

    private static int firstUpcomingIndex(VehiclePosition vehicle, int reference) {
        return vehicle.progress().status() == VehicleStopStatus.STOPPED_AT ? reference + 1 : reference;
    }
}
