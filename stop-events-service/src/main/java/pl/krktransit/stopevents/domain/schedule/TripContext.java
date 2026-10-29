package pl.krktransit.stopevents.domain.schedule;

import java.util.List;
import java.util.Map;
import java.util.NavigableMap;
import java.util.TreeMap;
import java.util.stream.Collectors;

public final class TripContext {
    private final String hash;
    private final String tripId;
    private final String lineNumber;
    private final Integer directionId;
    private final String headsign;
    private final int maxSequence;
    private final NavigableMap<Integer, ScheduledStop> stops;
    private final Map<String, List<Integer>> occurrences;

    public TripContext(String hash, String tripId, String lineNumber, Integer directionId, String headsign,
                       int maxSequence, List<ScheduledStop> stops) {
        this.hash = hash;
        this.tripId = tripId;
        this.lineNumber = lineNumber;
        this.directionId = directionId;
        this.headsign = headsign;
        this.maxSequence = maxSequence;
        this.stops = index(stops);
        this.occurrences = this.stops.values().stream().collect(Collectors.groupingBy(ScheduledStop::stopId,
                Collectors.mapping(ScheduledStop::sequence, Collectors.toUnmodifiableList())));
    }

    private static NavigableMap<Integer, ScheduledStop> index(List<ScheduledStop> stops) {
        var result = new TreeMap<Integer, ScheduledStop>();
        stops.forEach(stop -> result.put(stop.sequence(), stop));
        return java.util.Collections.unmodifiableNavigableMap(result);
    }

    public String hash() { return hash; }
    public String tripId() { return tripId; }
    public String lineNumber() { return lineNumber; }
    public Integer directionId() { return directionId; }
    public String headsign() { return headsign; }
    public int maxSequence() { return maxSequence; }
    public ScheduledStop stop(int sequence) { return stops.get(sequence); }
    public List<Integer> occurrences(String stopId) { return occurrences.getOrDefault(stopId, List.of()); }
    public List<ScheduledStop> between(int first, int last) {
        if (first > last) return List.of();
        return List.copyOf(stops.subMap(first, true, last, true).values());
    }
}
