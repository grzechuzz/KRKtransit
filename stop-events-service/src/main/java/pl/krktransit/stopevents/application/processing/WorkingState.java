package pl.krktransit.stopevents.application.processing;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import pl.krktransit.stopevents.application.port.DetectorStateStore;
import pl.krktransit.stopevents.domain.event.EventKey;
import pl.krktransit.stopevents.domain.event.StopEvent;
import pl.krktransit.stopevents.domain.source.SourceId;
import pl.krktransit.stopevents.domain.state.SavedStop;
import pl.krktransit.stopevents.domain.state.SavedStops;
import pl.krktransit.stopevents.domain.state.TripForecasts;
import pl.krktransit.stopevents.domain.state.TripKey;
import pl.krktransit.stopevents.domain.state.TripPin;
import pl.krktransit.stopevents.domain.state.VehicleKey;
import pl.krktransit.stopevents.domain.state.VehicleState;

public final class WorkingState {
    private final DetectorStateStore store;
    private final SourceId source;
    private final Duration inactivity;
    private final Map<VehicleKey, VehicleState> vehicles = new HashMap<>();
    private final Map<TripKey, TripPin> pins = new HashMap<>();
    private final Map<TripKey, TripForecasts> forecasts = new HashMap<>();
    private final Set<VehicleKey> loadedVehicles = new HashSet<>();
    private final Set<TripKey> loadedPins = new HashSet<>();
    private final Set<TripKey> loadedForecasts = new HashSet<>();
    private final Set<VehicleKey> changedVehicles = new HashSet<>();
    private final Set<TripKey> changedPins = new HashSet<>();
    private final Set<TripKey> changedForecasts = new HashSet<>();
    private final Set<TripDate> loadedSaved = new HashSet<>();
    private final Map<String, SourceId> tripSources = new HashMap<>();
    private final SavedStops saved = new SavedStops(Map.of());
    private final Map<EventKey, SavedStop> canonical = new HashMap<>();
    private final List<StopEvent> events = new ArrayList<>();

    public WorkingState(DetectorStateStore store, SourceId source, Duration inactivity) {
        this.store = store;
        this.source = source;
        this.inactivity = inactivity;
    }

    public VehicleState vehicle(String plate, Instant time) {
        var key = new VehicleKey(source, plate);
        if (loadedVehicles.add(key)) vehicles.put(key, store.vehicle(key));
        var state = vehicles.get(key);
        if (state != null && state.expired(time, inactivity)) deleteVehicle(plate);
        return vehicles.get(key);
    }

    public TripPin pin(String trip, Instant time) {
        var key = tripKey(trip);
        if (loadedPins.add(key)) pins.put(key, store.pin(key));
        var pin = pins.get(key);
        if (pin != null && pin.expired(time, inactivity)) pins.put(key, null);
        return pins.get(key);
    }

    public void observePin(String trip, TripPin pin, Instant time) {
        var key = tripKey(trip);
        pins.put(key, pin.observedAt(time));
        loadedPins.add(key);
        changedPins.add(key);
    }

    public TripForecasts forecasts(String trip, Instant time) {
        var key = tripKey(trip);
        if (loadedForecasts.add(key)) forecasts.put(key, store.forecasts(key));
        var value = forecasts.get(key);
        if (value != null && value.expired(time, inactivity)) deleteForecasts(trip);
        return forecasts.get(key);
    }

    public void saveForecasts(String trip, TripForecasts value) {
        var key = tripKey(trip);
        forecasts.put(key, value);
        loadedForecasts.add(key);
        changedForecasts.add(key);
    }

    public void deleteForecasts(String trip) { saveForecasts(trip, null); }

    public void saveVehicle(VehicleState state) {
        var key = new VehicleKey(source, state.licensePlate());
        vehicles.put(key, state);
        loadedVehicles.add(key);
        changedVehicles.add(key);
    }

    public void deleteVehicle(String plate) {
        var key = new VehicleKey(source, plate);
        vehicles.put(key, null);
        loadedVehicles.add(key);
        changedVehicles.add(key);
    }

    public void loadSaved(String trip, LocalDate date) {
        if (!loadedSaved.add(new TripDate(trip, date))) return;
        saved.putAll(store.savedStops(tripKey(trip), date));
        saved.putAll(canonical);
    }

    public void restore(Map<EventKey, SavedStop> stored) {
        canonical.putAll(stored);
        stored.keySet().forEach(key -> tripKey(key.tripId()));
        saved.putAll(stored);
    }

    public void stage(List<StopEvent> accepted) {
        accepted.forEach(saved::add);
        events.addAll(accepted);
    }

    public SavedStops saved() { return saved; }
    public List<StopEvent> events() { return List.copyOf(events); }

    public Map<TripKey, TripPin> changedPins() { return changedValues(pins, changedPins); }

    public StateChanges changes(String cursor, Map<EventKey, SavedStop> stored) {
        var savedChanges = new HashMap<>(canonical);
        events.forEach(event -> savedChanges.put(event.key(), stored.get(event.key())));
        return new StateChanges(changedValues(vehicles, changedVehicles), deleted(vehicles, changedVehicles),
                changedPins(), changedValues(forecasts, changedForecasts), deleted(forecasts, changedForecasts),
                savedChanges, tripSources, Map.of(source, cursor));
    }

    private TripKey tripKey(String trip) {
        tripSources.put(trip, source);
        return new TripKey(source, trip);
    }

    private static <K, V> Map<K, V> changedValues(Map<K, V> values, Set<K> changed) {
        var result = new HashMap<K, V>();
        changed.forEach(key -> { if (values.get(key) != null) result.put(key, values.get(key)); });
        return Map.copyOf(result);
    }

    private static <K, V> Set<K> deleted(Map<K, V> values, Set<K> changed) {
        var result = new HashSet<K>();
        changed.forEach(key -> { if (values.get(key) == null) result.add(key); });
        return Set.copyOf(result);
    }

    private record TripDate(String trip, LocalDate date) {}
}
