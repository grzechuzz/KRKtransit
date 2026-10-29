package pl.krktransit.stopevents.application.port;

import java.util.Map;
import java.util.Set;
import pl.krktransit.stopevents.application.processing.StateChanges;
import pl.krktransit.stopevents.domain.event.EventKey;
import pl.krktransit.stopevents.domain.source.SourceId;
import pl.krktransit.stopevents.domain.state.SavedStop;
import pl.krktransit.stopevents.domain.state.TripForecasts;
import pl.krktransit.stopevents.domain.state.TripKey;
import pl.krktransit.stopevents.domain.state.TripPin;
import pl.krktransit.stopevents.domain.state.VehicleKey;
import pl.krktransit.stopevents.domain.state.VehicleState;

public interface DetectorStateStore {
    void checkAvailable();
    VehicleState vehicle(VehicleKey key);
    TripPin pin(TripKey key);
    TripForecasts forecasts(TripKey key);
    Map<EventKey, SavedStop> savedStops(TripKey key, java.time.LocalDate date);
    Map<SourceId, String> cursors(Set<SourceId> sources);
    Map<TripKey, TripPin> pin(Map<TripKey, TripPin> pins);
    void commit(StateChanges changes);
}
