package pl.krktransit.stopevents.application.port;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.Set;
import pl.krktransit.stopevents.domain.event.EventKey;
import pl.krktransit.stopevents.domain.event.StopEvent;
import pl.krktransit.stopevents.domain.state.SavedStop;

public interface StopEventStore {
    void checkAvailable();
    Map<EventKey, SavedStop> storedStops(Set<String> tripIds, LocalDate since);
    Map<EventKey, SavedStop> insert(List<StopEvent> events);
}
