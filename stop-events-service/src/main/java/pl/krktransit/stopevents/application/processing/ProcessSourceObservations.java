package pl.krktransit.stopevents.application.processing;

import java.time.Instant;
import java.util.List;
import pl.krktransit.stopevents.application.port.ScheduleContexts;
import pl.krktransit.stopevents.domain.detection.EventFactory;
import pl.krktransit.stopevents.domain.detection.ForecastMerger;
import pl.krktransit.stopevents.domain.detection.TripFinalizer;
import pl.krktransit.stopevents.domain.detection.VehicleDetector;
import pl.krktransit.stopevents.domain.observation.ForecastUpdate;
import pl.krktransit.stopevents.domain.observation.Observation;
import pl.krktransit.stopevents.domain.observation.VehicleObservation;
import pl.krktransit.stopevents.domain.schedule.ScheduledStop;
import pl.krktransit.stopevents.domain.schedule.TripContext;
import pl.krktransit.stopevents.domain.state.TripPin;
import pl.krktransit.stopevents.domain.state.VehicleState;
import pl.krktransit.stopevents.domain.time.ServiceCalendar;

public final class ProcessSourceObservations {
    private static final System.Logger LOG = System.getLogger(ProcessSourceObservations.class.getName());
    private final ScheduleContexts schedule;
    private final VehicleDetector detector = new VehicleDetector();
    private final TripFinalizer finalizer = new TripFinalizer();
    private final ForecastMerger merger = new ForecastMerger();
    private final EventFactory eventFactory = new EventFactory();
    private final ServiceCalendar calendar = new ServiceCalendar();
    private long unknownTrips;
    private long unavailableVersions;
    private long invalidContexts;
    private long incompleteContexts;

    public ProcessSourceObservations(ScheduleContexts schedule) { this.schedule = schedule; }

    public void process(SourceBatch batch, WorkingState state) {
        for (var entry : batch.entries()) {
            switch (entry.observation()) {
                case VehicleObservation vehicle -> processVehicle(vehicle, state);
                case ForecastUpdate forecasts -> processForecasts(forecasts, state);
                case null -> {}
            }
        }
    }

    private void processForecasts(ForecastUpdate update, WorkingState state) {
        if (!update.eligible()) return;
        var trip = context(update, state);
        if (trip == null) return;
        var forecasts = state.forecasts(update.tripId(), update.fetchedAt());
        state.saveForecasts(update.tripId(), merger.merge(update, trip, forecasts));
    }

    private void processVehicle(VehicleObservation vehicle, WorkingState state) {
        if (!vehicle.eligible()) return;
        var previous = state.vehicle(vehicle.licensePlate(), vehicle.fetchedAt());
        if (previous != null && previous.switchesTo(vehicle.tripId())) {
            finishPrevious(vehicle, previous, state);
            previous = null;
        }
        var trip = context(vehicle, state);
        if (trip == null || trip.stop(vehicle.sequence()) == null) return;
        loadSavedForDetection(vehicle, previous, trip, state);
        diagnoseIncomplete(vehicle, trip);
        var outcome = detector.detect(vehicle, previous, trip,
                state.forecasts(vehicle.tripId(), vehicle.fetchedAt()), state.saved());
        state.stage(outcome.events());
        if (outcome.vehicleState() != null) state.saveVehicle(outcome.vehicleState());
    }

    private void finishPrevious(VehicleObservation vehicle, VehicleState previous, WorkingState state) {
        var pin = state.pin(previous.tripId(), vehicle.fetchedAt());
        var trip = pin == null ? null : lookup(pin.hash(), previous.tripId());
        if (trip != null) loadSavedForFinalization(previous, trip, state);
        var forecasts = state.forecasts(previous.tripId(), vehicle.fetchedAt());
        state.stage(finalizer.finalizeTrip(vehicle.source(), previous, trip, forecasts, state.saved(),
                vehicle.fetchedAt()));
        state.deleteForecasts(previous.tripId());
        state.deleteVehicle(vehicle.licensePlate());
    }

    private TripContext context(Observation observation, WorkingState state) {
        var pin = state.pin(observation.tripId(), observation.fetchedAt());
        if (pin != null) state.observePin(observation.tripId(), pin, observation.fetchedAt());
        String hash = pin == null ? schedule.currentHash(observation.source()) : pin.hash();
        var trip = lookup(hash, observation.tripId());
        if (trip == null) return null;
        var selected = pin == null ? new TripPin(hash, observation.fetchedAt()) : pin;
        state.observePin(observation.tripId(), selected, observation.fetchedAt());
        return trip;
    }

    private TripContext lookup(String hash, String trip) {
        return switch (schedule.tripContext(hash, trip)) {
            case ScheduleContexts.Found found -> found.context();
            case ScheduleContexts.UnknownTrip ignored -> missing("unknown trip", trip, hash, ++unknownTrips);
            case ScheduleContexts.VersionUnavailable ignored -> missing("unavailable version", trip, hash, ++unavailableVersions);
            case ScheduleContexts.InvalidContext invalid ->
                    missing("invalid context (" + invalid.reason() + ")", trip, hash, ++invalidContexts);
        };
    }

    private TripContext missing(String reason, String trip, String hash, long count) {
        LOG.log(level(count), "Schedule {0}: trip={1}, hash={2}, observations={3}", reason, trip, hash, count);
        return null;
    }

    private void loadSavedForDetection(VehicleObservation vehicle, VehicleState previous, TripContext trip,
                                       WorkingState state) {
        int first = previous == null ? vehicle.sequence() : Math.min(previous.sequence(), vehicle.sequence());
        loadSaved(trip.tripId(), vehicle.timestamp(), trip.between(first, vehicle.sequence()), state);
    }

    private void loadSavedForFinalization(VehicleState previous, TripContext trip, WorkingState state) {
        loadSaved(previous.tripId(), previous.timestamp(), trip.between(previous.sequence(), trip.maxSequence()), state);
    }

    private void loadSaved(String tripId, Instant timestamp, List<ScheduledStop> stops, WorkingState state) {
        stops.stream().map(stop -> calendar.serviceDate(timestamp, stop.arrivalSeconds())).distinct()
                .forEach(date -> state.loadSaved(tripId, date));
    }

    private void diagnoseIncomplete(VehicleObservation vehicle, TripContext trip) {
        if (eventFactory.complete(trip, vehicle.licensePlate())) return;
        long count = ++incompleteContexts;
        LOG.log(level(count), "Incomplete stop-event context: trip={0}, observations={1}", trip.tripId(), count);
    }

    private System.Logger.Level level(long count) {
        return count % 1000 == 1 ? System.Logger.Level.WARNING : System.Logger.Level.DEBUG;
    }
}
