package pl.krktransit.livevehicles.application;

public final class ObservationFailure extends RuntimeException {
    public ObservationFailure(String message, Throwable cause) { super(message, cause); }
}
