package pl.krktransit.stopevents.domain.state;

import java.time.LocalDate;
import java.util.HashMap;
import java.util.Map;
import java.util.NavigableMap;
import java.util.TreeMap;
import pl.krktransit.stopevents.domain.event.EventKey;
import pl.krktransit.stopevents.domain.event.StopEvent;

public final class SavedStops {
    private final Map<TripDate, NavigableMap<Integer, SavedStop>> trips = new HashMap<>();

    public SavedStops(Map<EventKey, SavedStop> stops) { putAll(stops); }

    private SavedStops(SavedStops original) {
        original.trips.forEach((key, stops) -> trips.put(key, new TreeMap<>(stops)));
    }

    public SavedStops copy() { return new SavedStops(this); }

    public boolean contains(String trip, LocalDate date, int sequence) {
        var stops = trips.get(new TripDate(trip, date));
        return stops != null && stops.containsKey(sequence);
    }

    public SavedStop at(String trip, LocalDate date, int sequence) {
        var stops = trips.get(new TripDate(trip, date));
        return stops == null ? null : stops.get(sequence);
    }

    public SavedStop previous(String trip, LocalDate date, int sequence) {
        var stops = trips.get(new TripDate(trip, date));
        return stops == null ? null : value(stops.lowerEntry(sequence));
    }

    public SavedStop next(String trip, LocalDate date, int sequence) {
        var stops = trips.get(new TripDate(trip, date));
        return stops == null ? null : value(stops.higherEntry(sequence));
    }

    public void add(StopEvent event) { put(event.key(), event.savedStop()); }
    public void putAll(Map<EventKey, SavedStop> values) { values.forEach(this::put); }

    public Map<EventKey, SavedStop> values() {
        var values = new HashMap<EventKey, SavedStop>();
        trips.forEach((trip, stops) -> stops.forEach((sequence, stop) ->
                values.put(new EventKey(trip.tripId(), trip.date(), sequence), stop)));
        return Map.copyOf(values);
    }

    private void put(EventKey key, SavedStop stop) {
        trips.computeIfAbsent(new TripDate(key.tripId(), key.serviceDate()), ignored -> new TreeMap<>())
                .put(key.sequence(), stop);
    }

    private SavedStop value(Map.Entry<Integer, SavedStop> entry) { return entry == null ? null : entry.getValue(); }

    private record TripDate(String tripId, LocalDate date) {}
}
