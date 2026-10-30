package pl.krktransit.stopevents.domain.detection;

import java.util.ArrayList;
import java.util.List;
import pl.krktransit.stopevents.domain.event.StopEvent;
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
        return chronologicallyFits(event, saved) && delayFits(event, saved);
    }

    private boolean chronologicallyFits(StopEvent event, SavedStops saved) {
        var previous = saved.previous(event.tripId(), event.serviceDate(), event.sequence());
        var next = saved.next(event.tripId(), event.serviceDate(), event.sequence());
        return (previous == null || previous.eventTime().isBefore(event.eventTime()))
                && (next == null || event.eventTime().isBefore(next.eventTime()));
    }

    private boolean delayFits(StopEvent event, SavedStops saved) {
        var previous = saved.at(event.tripId(), event.serviceDate(), event.sequence() - 1);
        var next = saved.at(event.tripId(), event.serviceDate(), event.sequence() + 1);
        return (previous == null || allowedDrop(previous.delaySeconds(), event.delaySeconds()))
                && (next == null || allowedDrop(event.delaySeconds(), next.delaySeconds()));
    }

    private boolean allowedDrop(int previous, int next) { return (long) previous - next <= DELAY_DROP_LIMIT_SECONDS; }

    private boolean implausible(StopEvent event) {
        return event.delaySeconds() < -earlyLimit(event) || event.delaySeconds() > LATE_LIMIT_SECONDS;
    }

    private int earlyLimit(StopEvent event) {
        return event.sequence() == 1 ? FIRST_STOP_EARLY_LIMIT_SECONDS : EARLY_ARRIVAL_LIMIT_SECONDS;
    }
}
