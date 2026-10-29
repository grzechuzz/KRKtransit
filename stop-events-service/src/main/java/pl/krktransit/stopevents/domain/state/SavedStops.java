package pl.krktransit.stopevents.domain.state;

import java.time.LocalDate;
import java.util.HashMap;
import java.util.Map;
import pl.krktransit.stopevents.domain.event.EventKey;
import pl.krktransit.stopevents.domain.event.StopEvent;

public final class SavedStops {
    private final Map<EventKey, SavedStop> stops;

    public SavedStops(Map<EventKey, SavedStop> stops) { this.stops = new HashMap<>(stops); }
    public SavedStops copy() { return new SavedStops(stops); }
    public boolean contains(String trip, LocalDate date, int sequence) {
        return stops.containsKey(new EventKey(trip, date, sequence));
    }
    public SavedStop previous(String trip, LocalDate date, int sequence) {
        return stops.get(new EventKey(trip, date, sequence - 1));
    }
    public void add(StopEvent event) { stops.put(event.key(), event.savedStop()); }
    public void putAll(Map<EventKey, SavedStop> values) { stops.putAll(values); }
    public Map<EventKey, SavedStop> values() { return Map.copyOf(stops); }
}
