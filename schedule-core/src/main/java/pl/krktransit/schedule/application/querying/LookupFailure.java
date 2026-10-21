package pl.krktransit.schedule.application.querying;

public final class LookupFailure extends RuntimeException {
    public enum Kind { UNKNOWN_FEED, NOT_PUBLISHED, VERSION_UNAVAILABLE, ENTITY_NOT_FOUND }
    private final Kind kind;

    public LookupFailure(Kind kind, String message) {
        super(message);
        this.kind = kind;
    }

    public Kind kind() { return kind; }
}
