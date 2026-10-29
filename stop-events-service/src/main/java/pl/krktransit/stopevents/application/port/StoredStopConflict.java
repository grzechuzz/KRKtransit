package pl.krktransit.stopevents.application.port;

import java.util.Map;
import pl.krktransit.stopevents.domain.event.EventKey;
import pl.krktransit.stopevents.domain.state.SavedStop;

public final class StoredStopConflict extends RuntimeException {
    private final Map<EventKey, SavedStop> stored;
    public StoredStopConflict(Map<EventKey, SavedStop> stored) {
        super("Replay must use the stored stop events");
        this.stored = Map.copyOf(stored);
    }
    public Map<EventKey, SavedStop> stored() { return stored; }
}
