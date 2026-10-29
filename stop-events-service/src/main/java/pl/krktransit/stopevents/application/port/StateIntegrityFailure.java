package pl.krktransit.stopevents.application.port;

public final class StateIntegrityFailure extends IllegalStateException {
    public StateIntegrityFailure(String message) { super(message); }
    public StateIntegrityFailure(String message, Throwable cause) { super(message, cause); }
}
