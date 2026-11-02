package pl.krktransit.publicapi.application.upcoming;

import java.util.Map;
import java.util.OptionalInt;

public record PredictedDelays(Map<Integer, Integer> secondsBySequence) {
    public static final PredictedDelays NONE = new PredictedDelays(Map.of());

    public PredictedDelays { secondsBySequence = Map.copyOf(secondsBySequence); }

    public OptionalInt forSequence(int sequence) {
        var seconds = secondsBySequence.get(sequence);
        return seconds == null ? OptionalInt.empty() : OptionalInt.of(seconds);
    }
}
