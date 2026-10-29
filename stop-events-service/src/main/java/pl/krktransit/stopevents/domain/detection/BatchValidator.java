package pl.krktransit.stopevents.domain.detection;

import java.util.List;
import pl.krktransit.stopevents.domain.event.DetectionMethod;
import pl.krktransit.stopevents.domain.event.StopEvent;

public final class BatchValidator {
    private static final int DELAY_DROP_LIMIT_SECONDS = 180;

    public List<StopEvent> validate(List<StopEvent> events) {
        var reference = events.stream().filter(event -> event.method() == DetectionMethod.STOPPED_AT)
                .reduce((first, second) -> second).orElse(null);
        if (reference == null) return events;
        return events.stream().filter(event -> keep(event, reference)).toList();
    }

    private boolean keep(StopEvent event, StopEvent reference) {
        if (!event.estimated() || event.sequence() >= reference.sequence()) return true;
        return !event.eventTime().isAfter(reference.eventTime())
                && (long) event.delaySeconds() - reference.delaySeconds() <= DELAY_DROP_LIMIT_SECONDS;
    }
}
