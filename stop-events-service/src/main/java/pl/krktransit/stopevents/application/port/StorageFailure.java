package pl.krktransit.stopevents.application.port;

public final class StorageFailure extends RuntimeException {
    public enum Kind { REDIS, POSTGRES, UNCERTAIN_COMMIT }
    private final Kind kind;

    public StorageFailure(Kind kind, String message, Throwable cause) {
        super(message, cause);
        this.kind = kind;
    }

    public Kind kind() { return kind; }
}
