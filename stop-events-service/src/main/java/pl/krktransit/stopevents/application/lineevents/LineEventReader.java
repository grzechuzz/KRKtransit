package pl.krktransit.stopevents.application.lineevents;

import pl.krktransit.stopevents.domain.lineevents.LineEventsPage;
import pl.krktransit.stopevents.domain.lineevents.LineEventsQuery;

@FunctionalInterface
public interface LineEventReader {
    LineEventsPage read(LineEventsQuery query);
}
