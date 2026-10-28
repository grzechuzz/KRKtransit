package pl.krktransit.realtime.ingestion.application.collection;

public final class DecodeFailure extends RuntimeException {
    public DecodeFailure(String message) {
        super(message);
    }

    public DecodeFailure(String message, Throwable cause) {
        super(message, cause);
    }
}
