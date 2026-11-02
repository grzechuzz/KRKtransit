package pl.krktransit.stopevents.application.lineevents;

public final class LineEventsFailure extends RuntimeException {
    public enum Kind { UNAVAILABLE, BUSY }
    private final Kind kind;

    public LineEventsFailure(Kind kind, String message) { this(kind, message, null); }

    public LineEventsFailure(Kind kind, String message, Throwable cause) {
        super(message, cause);
        this.kind = kind;
    }

    public Kind kind() { return kind; }
}
