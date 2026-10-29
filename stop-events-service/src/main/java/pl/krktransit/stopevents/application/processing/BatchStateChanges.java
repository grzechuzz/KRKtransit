package pl.krktransit.stopevents.application.processing;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import pl.krktransit.stopevents.domain.event.EventKey;
import pl.krktransit.stopevents.domain.source.SourceId;
import pl.krktransit.stopevents.domain.state.SavedStop;
import pl.krktransit.stopevents.domain.state.TripForecasts;
import pl.krktransit.stopevents.domain.state.TripKey;
import pl.krktransit.stopevents.domain.state.TripPin;
import pl.krktransit.stopevents.domain.state.VehicleKey;
import pl.krktransit.stopevents.domain.state.VehicleState;

final class BatchStateChanges {
    private final Map<VehicleKey, VehicleState> vehicles = new HashMap<>();
    private final Set<VehicleKey> deletedVehicles = new HashSet<>();
    private final Map<TripKey, TripPin> pins = new HashMap<>();
    private final Map<TripKey, TripForecasts> forecasts = new HashMap<>();
    private final Set<TripKey> deletedForecasts = new HashSet<>();
    private final Map<EventKey, SavedStop> saved = new HashMap<>();
    private final Map<String, SourceId> sources = new HashMap<>();
    private final Map<SourceId, String> cursors = new HashMap<>();

    void add(StateChanges changes) {
        vehicles.putAll(changes.vehicles());
        deletedVehicles.addAll(changes.deletedVehicles());
        pins.putAll(changes.pins());
        forecasts.putAll(changes.forecasts());
        deletedForecasts.addAll(changes.deletedForecasts());
        saved.putAll(changes.savedStops());
        sources.putAll(changes.tripSources());
        cursors.putAll(changes.cursors());
    }

    StateChanges snapshot() {
        return new StateChanges(vehicles, deletedVehicles, pins, forecasts, deletedForecasts, saved, sources, cursors);
    }
}
