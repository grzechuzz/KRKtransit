package pl.krktransit.stopevents.domain.detection;

import java.util.ArrayList;
import java.util.List;
import pl.krktransit.stopevents.domain.event.StopEvent;
import pl.krktransit.stopevents.domain.state.SavedStop;
import pl.krktransit.stopevents.domain.state.SavedStops;

public final class EventValidator {
    private static final int EARLY_ARRIVAL_LIMIT_SECONDS = 180;
    private static final int FIRST_STOP_EARLY_LIMIT_SECONDS = 3600;
    private static final int LATE_LIMIT_SECONDS = 10800;
    private static final int DELAY_DROP_LIMIT_SECONDS = 180;

    public List<StopEvent> validate(List<StopEvent> events, SavedStops saved) {
        var candidates = saved.copy();
        var accepted = new ArrayList<StopEvent>();
        for (var event : events) {
            if (!valid(event, candidates)) continue;
            accepted.add(event);
            candidates.add(event);
        }
        return List.copyOf(accepted);
    }

    private boolean valid(StopEvent event, SavedStops saved) {
        if (implausible(event)) return false;
        if (event.sequence() <= 1) return true;
        var previous = saved.previous(event.tripId(), event.serviceDate(), event.sequence());
        return previous == null || follows(previous, event);
    }

    private boolean follows(SavedStop previous, StopEvent event) {
        return (long) previous.delaySeconds() - event.delaySeconds() <= DELAY_DROP_LIMIT_SECONDS
                && previous.eventTime().isBefore(event.eventTime());
    }

    private boolean implausible(StopEvent event) {
        return event.delaySeconds() < -earlyLimit(event) || event.delaySeconds() > LATE_LIMIT_SECONDS;
    }

    private int earlyLimit(StopEvent event) {
        return event.sequence() == 1 ? FIRST_STOP_EARLY_LIMIT_SECONDS : EARLY_ARRIVAL_LIMIT_SECONDS;
    }
}
