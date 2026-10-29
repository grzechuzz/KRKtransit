package pl.krktransit.stopevents.application.processing;

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

public record StateChanges(Map<VehicleKey, VehicleState> vehicles, Set<VehicleKey> deletedVehicles,
                           Map<TripKey, TripPin> pins, Map<TripKey, TripForecasts> forecasts,
                           Set<TripKey> deletedForecasts, Map<EventKey, SavedStop> savedStops,
                           Map<String, SourceId> tripSources, Map<SourceId, String> cursors) {
    public StateChanges {
        vehicles = Map.copyOf(vehicles);
        deletedVehicles = Set.copyOf(deletedVehicles);
        pins = Map.copyOf(pins);
        forecasts = Map.copyOf(forecasts);
        deletedForecasts = Set.copyOf(deletedForecasts);
        savedStops = Map.copyOf(savedStops);
        tripSources = Map.copyOf(tripSources);
        cursors = Map.copyOf(cursors);
    }
}
