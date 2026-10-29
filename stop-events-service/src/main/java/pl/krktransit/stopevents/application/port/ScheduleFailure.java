package pl.krktransit.stopevents.application.port;

public final class ScheduleFailure extends RuntimeException {
    public ScheduleFailure(String message, Throwable cause) { super(message, cause); }
}
