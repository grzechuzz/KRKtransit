package pl.krktransit.stopevents.domain.lineevents;

import java.util.List;
import java.util.Optional;

public record LineEventsPage(List<LineEvent> events, boolean hasMore) {
    public LineEventsPage { events = List.copyOf(events); }

    public Optional<LineEvent> last() { return events.isEmpty() ? Optional.empty() : Optional.of(events.getLast()); }
}
