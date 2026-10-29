package pl.krktransit.stopevents.application.statistics;

public final class StatisticsFailure extends RuntimeException {
    public enum Kind { NOT_FOUND, UNAVAILABLE, BUSY, CANCELLED }
    private final Kind kind;

    public StatisticsFailure(Kind kind, String message) { this(kind, message, null); }

    public StatisticsFailure(Kind kind, String message, Throwable cause) {
        super(message, cause);
        this.kind = kind;
    }

    public Kind kind() { return kind; }
}
