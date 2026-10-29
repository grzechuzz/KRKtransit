package pl.krktransit.stopevents.application.processing;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import pl.krktransit.stopevents.application.port.DetectorStateStore;
import pl.krktransit.stopevents.application.port.ScheduleContexts;
import pl.krktransit.stopevents.application.port.ScheduleFailure;
import pl.krktransit.stopevents.application.port.StateIntegrityFailure;
import pl.krktransit.stopevents.application.port.StopEventStore;
import pl.krktransit.stopevents.application.port.StorageFailure;
import pl.krktransit.stopevents.application.port.StoredStopConflict;
import pl.krktransit.stopevents.domain.event.EventKey;
import pl.krktransit.stopevents.domain.observation.Observation;
import pl.krktransit.stopevents.domain.observation.VehicleObservation;
import pl.krktransit.stopevents.domain.source.SourceId;
import pl.krktransit.stopevents.domain.state.SavedStop;
import pl.krktransit.stopevents.domain.state.TripKey;
import pl.krktransit.stopevents.domain.state.TripPin;
import pl.krktransit.stopevents.domain.state.VehicleKey;
import pl.krktransit.stopevents.domain.time.ServiceCalendar;

public final class ProcessObservationBatch {
    private static final System.Logger LOG = System.getLogger(ProcessObservationBatch.class.getName());
    private final DetectorStateStore state;
    private final StopEventStore events;
    private final ProcessSourceObservations processor;
    private final ServiceCalendar calendar = new ServiceCalendar();
    private final Duration inactivity;
    private final Set<SourceId> recovered = new HashSet<>();

    public ProcessObservationBatch(DetectorStateStore state, StopEventStore events,
                                    ScheduleContexts schedule, Duration inactivity) {
        this.state = state;
        this.events = events;
        this.processor = new ProcessSourceObservations(schedule);
        this.inactivity = inactivity;
    }

    public BatchOutcome process(List<SourceBatch> batches) {
        var restored = new HashMap<EventKey, SavedStop>();
        try {
            return retryConflicts(batches, restored);
        } catch (StorageFailure failure) {
            batches.forEach(batch -> recovered.remove(batch.source()));
            throw failure;
        }
    }

    public void requireRecovery(Set<SourceId> sources) { recovered.removeAll(sources); }

    private BatchOutcome retryConflicts(List<SourceBatch> batches, Map<EventKey, SavedStop> restored) {
        for (int attempt = 0; attempt < 4; attempt++) {
            var prepared = prepare(batches, restored);
            if (!pinsMatch(prepared.states())) continue;
            try {
                return persist(prepared);
            } catch (StoredStopConflict conflict) {
                restored.putAll(conflict.stored());
            }
        }
        throw new StateIntegrityFailure("Repeated stop-event conflicts; only one detector may own the streams");
    }

    private PreparedBatch prepare(List<SourceBatch> batches, Map<EventKey, SavedStop> restored) {
        var working = new LinkedHashMap<SourceBatch, WorkingState>();
        var paused = new HashSet<SourceId>();
        for (var batch : batches) {
            if (batch.entries().isEmpty()) continue;
            try {
                working.put(batch, prepareSource(batch, restored));
            } catch (ScheduleFailure failure) {
                LOG.log(System.Logger.Level.WARNING, "Schedule paused source " + batch.source(), failure);
                paused.add(batch.source());
            }
        }
        return new PreparedBatch(working, paused);
    }

    private WorkingState prepareSource(SourceBatch batch, Map<EventKey, SavedStop> restored) {
        var working = new WorkingState(state, batch.source(), inactivity);
        restoreSavedStops(batch, working, restored);
        processor.process(batch, working);
        return working;
    }

    private void restoreSavedStops(SourceBatch batch, WorkingState working, Map<EventKey, SavedStop> restored) {
        boolean recovery = !recovered.contains(batch.source());
        if (!recovery && restored.isEmpty()) return;
        var trips = referencedTrips(batch);
        if (recovery && !trips.isEmpty()) working.restore(events.storedStops(trips, recoveryStart(batch)));
        working.restore(forTrips(restored, trips));
    }

    private Set<String> referencedTrips(SourceBatch batch) {
        var trips = new HashSet<String>();
        for (var entry : batch.entries()) {
            var observation = entry.observation();
            if (observation == null) continue;
            if (observation.tripId() != null) trips.add(observation.tripId());
            if (observation instanceof VehicleObservation vehicle && vehicle.eligible()) {
                var previous = state.vehicle(new VehicleKey(vehicle.source(), vehicle.licensePlate()));
                if (previous != null) trips.add(previous.tripId());
            }
        }
        return Set.copyOf(trips);
    }

    private LocalDate recoveryStart(SourceBatch batch) {
        var earliest = batch.entries().stream().map(StreamEntry::observation).filter(Objects::nonNull)
                .map(Observation::fetchedAt).min(Instant::compareTo).orElseThrow();
        return calendar.earliestServiceDate(earliest.minus(inactivity));
    }

    private Map<EventKey, SavedStop> forTrips(Map<EventKey, SavedStop> restored, Set<String> trips) {
        var selected = new HashMap<EventKey, SavedStop>();
        restored.forEach((key, value) -> { if (trips.contains(key.tripId())) selected.put(key, value); });
        return selected;
    }

    private boolean pinsMatch(Map<SourceBatch, WorkingState> working) {
        var requested = new HashMap<TripKey, TripPin>();
        working.values().forEach(value -> requested.putAll(value.changedPins()));
        var stored = state.pin(requested);
        return requested.entrySet().stream()
                .allMatch(entry -> entry.getValue().hash().equals(stored.get(entry.getKey()).hash()));
    }

    private BatchOutcome persist(PreparedBatch prepared) {
        var pending = prepared.states().values().stream().flatMap(value -> value.events().stream()).toList();
        var stored = pending.isEmpty() ? Map.<EventKey, SavedStop>of() : events.insert(pending);
        var changes = mergeChanges(prepared.states(), stored);
        if (!changes.cursors().isEmpty()) commit(changes);
        recovered.addAll(changes.cursors().keySet());
        return new BatchOutcome(changes.cursors(), prepared.paused(), pending.size());
    }

    private void commit(StateChanges changes) {
        try {
            state.commit(changes);
        } catch (StorageFailure failure) {
            if (failure.kind() != StorageFailure.Kind.UNCERTAIN_COMMIT || !alreadyCommitted(changes)) throw failure;
        }
    }

    private boolean alreadyCommitted(StateChanges changes) {
        var cursors = state.cursors(changes.cursors().keySet());
        return changes.cursors().equals(cursors);
    }

    private StateChanges mergeChanges(Map<SourceBatch, WorkingState> working, Map<EventKey, SavedStop> stored) {
        var changes = new BatchStateChanges();
        working.forEach((batch, overlay) -> changes.add(overlay.changes(batch.endCursor(), stored)));
        return changes.snapshot();
    }

    private record PreparedBatch(Map<SourceBatch, WorkingState> states, Set<SourceId> paused) {}
}
