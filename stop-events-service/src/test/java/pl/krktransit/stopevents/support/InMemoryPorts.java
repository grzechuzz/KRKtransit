package pl.krktransit.stopevents.support;

import java.time.Duration;
import java.time.LocalDate;
import java.util.*;
import pl.krktransit.stopevents.application.port.*;
import pl.krktransit.stopevents.application.processing.StateChanges;
import pl.krktransit.stopevents.domain.event.*;
import pl.krktransit.stopevents.domain.schedule.TripContext;
import pl.krktransit.stopevents.domain.source.SourceId;
import pl.krktransit.stopevents.domain.state.*;

public final class InMemoryPorts {
    private InMemoryPorts() {}

    public static final class State implements DetectorStateStore {
        public final Map<VehicleKey, VehicleState> vehicles = new HashMap<>();
        public final Map<TripKey, TripPin> pins = new HashMap<>();
        public final Map<TripKey, TripForecasts> forecasts = new HashMap<>();
        public final Map<EventKey, SavedStop> saved = new HashMap<>();
        public final Map<SourceId, String> positions = new HashMap<>();
        public final List<String> operations = new ArrayList<>();
        public boolean failBeforeCommit;
        public boolean failAfterCommit;
        public boolean corruptCommit;

        public void checkAvailable() {}
        public VehicleState vehicle(VehicleKey key) { return vehicles.get(key); }
        public TripPin pin(TripKey key) { return pins.get(key); }
        public TripForecasts forecasts(TripKey key) { return forecasts.get(key); }
        public Map<EventKey, SavedStop> savedStops(TripKey key, LocalDate date) {
            var selected = new HashMap<EventKey, SavedStop>();
            saved.forEach((event, value) -> {
                if (event.tripId().equals(key.tripId()) && event.serviceDate().equals(date)) selected.put(event, value);
            });
            return selected;
        }
        public Map<SourceId, String> cursors(Set<SourceId> sources) {
            var selected = new HashMap<SourceId, String>();
            sources.forEach(source -> { if (positions.containsKey(source)) selected.put(source, positions.get(source)); });
            return selected;
        }
        public Map<TripKey, TripPin> pin(Map<TripKey, TripPin> requested) {
            operations.add("pins");
            requested.forEach((key, pin) -> {
                var existing = pins.get(key);
                if (existing == null || existing.expired(pin.fetchedAt(), Duration.ofHours(3))) pins.put(key, pin);
            });
            var stored = new HashMap<TripKey, TripPin>();
            requested.keySet().forEach(key -> stored.put(key, pins.get(key)));
            return stored;
        }
        public void commit(StateChanges changes) {
            operations.add("commit");
            if (corruptCommit) throw new StateIntegrityFailure("partial commit");
            if (failBeforeCommit) { failBeforeCommit = false; throw uncertain(); }
            changes.deletedVehicles().forEach(vehicles::remove);
            changes.deletedForecasts().forEach(forecasts::remove);
            vehicles.putAll(changes.vehicles());
            pins.putAll(changes.pins());
            forecasts.putAll(changes.forecasts());
            saved.putAll(changes.savedStops());
            positions.putAll(changes.cursors());
            if (failAfterCommit) { failAfterCommit = false; throw uncertain(); }
        }
        private StorageFailure uncertain() {
            return new StorageFailure(StorageFailure.Kind.UNCERTAIN_COMMIT, "lost EXEC reply", null);
        }
    }

    public static final class Events implements StopEventStore {
        public final Map<EventKey, StopEvent> rows = new LinkedHashMap<>();
        public final List<Set<String>> recoveryReads = new ArrayList<>();
        public final List<LocalDate> recoveryStarts = new ArrayList<>();
        public boolean failAfterInsert;
        public int rolledBack;
        public Runnable beforeInsert = () -> {};

        public void checkAvailable() {}
        public Map<EventKey, SavedStop> storedStops(Set<String> trips, LocalDate since) {
            recoveryReads.add(Set.copyOf(trips));
            recoveryStarts.add(since);
            var stored = new HashMap<EventKey, SavedStop>();
            rows.forEach((key, value) -> {
                if (trips.contains(key.tripId()) && !key.serviceDate().isBefore(since)) stored.put(key, value.savedStop());
            });
            return stored;
        }
        public Map<EventKey, SavedStop> insert(List<StopEvent> events) {
            beforeInsert.run();
            var conflicts = new HashMap<EventKey, SavedStop>();
            events.forEach(event -> {
                var stored = rows.get(event.key());
                if (stored != null && !stored.savedStop().equals(event.savedStop())) conflicts.put(event.key(), stored.savedStop());
            });
            if (!conflicts.isEmpty()) { rolledBack++; throw new StoredStopConflict(conflicts); }
            events.forEach(event -> rows.putIfAbsent(event.key(), event));
            if (failAfterInsert) {
                failAfterInsert = false;
                throw new StorageFailure(StorageFailure.Kind.POSTGRES, "uncertain SQL commit", null);
            }
            var stored = new HashMap<EventKey, SavedStop>();
            events.forEach(event -> stored.put(event.key(), rows.get(event.key()).savedStop()));
            return stored;
        }
    }

    public static final class Schedule implements ScheduleContexts {
        public String hash = DetectorFixtures.HASH;
        public final Map<String, TripContext> trips = new HashMap<>();
        public final Set<SourceId> unavailable = new HashSet<>();
        public boolean unknown;
        public boolean versionUnavailable;
        public boolean invalid;
        public int hashReads;
        public final List<String> contextHashes = new ArrayList<>();

        public String currentHash(SourceId source) {
            if (unavailable.contains(source)) throw new ScheduleFailure("not published", null);
            hashReads++;
            return hash;
        }
        public Lookup tripContext(String selected, String trip) {
            contextHashes.add(selected);
            if (unknown) return new UnknownTrip();
            if (versionUnavailable) return new VersionUnavailable();
            if (invalid) return new InvalidContext("incomplete stop occurrence");
            var context = trips.get(trip);
            if (context == null) context = DetectorFixtures.trip(selected, trip, 0, "Destination");
            return new Found(context);
        }
    }
}
