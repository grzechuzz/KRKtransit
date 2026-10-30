package pl.krktransit.livevehicles.domain;

public record StopProgress(String stopId, Integer sequence, VehicleStopStatus status) {
    public static final StopProgress NONE = new StopProgress(null, null, null);

    public StopProgress {
        if (!validSequence(sequence)) throw new IllegalArgumentException("Stop sequence cannot be negative");
    }

    public boolean behind(StopProgress other) {
        return sequence != null && other.sequence != null && sequence < other.sequence;
    }

    private static boolean validSequence(Integer sequence) { return sequence == null || sequence >= 0; }
}
