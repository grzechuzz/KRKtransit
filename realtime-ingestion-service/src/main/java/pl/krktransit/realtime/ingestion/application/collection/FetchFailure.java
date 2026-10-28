package pl.krktransit.realtime.ingestion.application.collection;

import java.time.Duration;
import java.util.Objects;

public final class FetchFailure extends RuntimeException {
    public enum Kind { TRANSPORT, TIMEOUT, HTTP_STATUS, ACCESS_DENIED, BODY_TOO_LARGE, CANCELLED }
    private final Kind kind;
    private final int status;
    private final Duration retryAfter;

    public FetchFailure(Kind kind, String message, Throwable cause) {
        this(kind, 0, Duration.ZERO, message, cause);
    }

    public FetchFailure(Kind kind, int status, Duration retryAfter, String message, Throwable cause) {
        super(message, cause);
        this.kind = Objects.requireNonNull(kind, "kind");
        this.status = status;
        this.retryAfter = Objects.requireNonNull(retryAfter, "retryAfter");
    }

    public Kind kind() { return kind; }

    public int status() { return status; }

    public Duration retryAfter() { return retryAfter; }
}
