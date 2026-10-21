package pl.krktransit.schedule.application.port;

import pl.krktransit.schedule.domain.feed.FeedId;
import pl.krktransit.schedule.domain.schedule.ScheduleHash;
import pl.krktransit.schedule.application.querying.TripContext;
import pl.krktransit.schedule.application.querying.ShapeContext;

public interface ScheduleReader {
    ScheduleHash currentHash(FeedId feed);
    TripContext tripContext(ScheduleHash hash, String tripId);
    ShapeContext shape(ScheduleHash hash, String shapeId);
}
