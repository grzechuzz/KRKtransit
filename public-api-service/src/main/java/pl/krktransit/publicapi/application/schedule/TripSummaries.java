package pl.krktransit.publicapi.application.schedule;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

public record TripSummaries(Map<String, TripSummary> found, Set<String> missing) {
    public TripSummaries {
        found = Map.copyOf(found);
        missing = Set.copyOf(missing);
    }

    public static TripSummaries none() { return new TripSummaries(Map.of(), Set.of()); }

    public boolean resolves(String tripId) { return found.containsKey(tripId) || missing.contains(tripId); }

    public TripSummaries with(TripSummaries other) {
        var mergedFound = new HashMap<>(found);
        mergedFound.putAll(other.found);
        var mergedMissing = new HashSet<>(missing);
        mergedMissing.addAll(other.missing);
        return new TripSummaries(mergedFound, mergedMissing);
    }
}
