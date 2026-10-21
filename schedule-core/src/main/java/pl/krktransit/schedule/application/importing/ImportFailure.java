package pl.krktransit.schedule.application.importing;

public final class ImportFailure extends RuntimeException {
    public enum Kind { DOWNLOAD, ARCHIVE, VALIDATION, PERSISTENCE, CANCELLED }
    private final Kind kind;

    public ImportFailure(Kind kind, String message) {
        super(message);
        this.kind = kind;
    }

    public ImportFailure(Kind kind, String message, Throwable cause) {
        super(message, cause);
        this.kind = kind;
    }

    public Kind kind() { return kind; }
}
