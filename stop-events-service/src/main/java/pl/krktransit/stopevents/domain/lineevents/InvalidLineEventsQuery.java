package pl.krktransit.stopevents.domain.lineevents;

public final class InvalidLineEventsQuery extends IllegalArgumentException {
    public InvalidLineEventsQuery(String message) { super(message); }

    public InvalidLineEventsQuery(String message, Throwable cause) { super(message, cause); }
}
