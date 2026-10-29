package pl.krktransit.stopevents.application.port;

import pl.krktransit.stopevents.domain.schedule.TripContext;
import pl.krktransit.stopevents.domain.source.SourceId;

public interface ScheduleContexts {
    String currentHash(SourceId source);
    Lookup tripContext(String hash, String tripId);

    sealed interface Lookup permits Found, UnknownTrip, VersionUnavailable, InvalidContext {}
    record Found(TripContext context) implements Lookup {}
    record UnknownTrip() implements Lookup {}
    record VersionUnavailable() implements Lookup {}
    record InvalidContext(String reason) implements Lookup {}
}
