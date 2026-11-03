package pl.krktransit.schedule.application.port;

import java.util.List;
import pl.krktransit.schedule.application.querying.ShapeContext;
import pl.krktransit.schedule.application.querying.TripContext;
import pl.krktransit.schedule.domain.feed.FeedId;
import pl.krktransit.schedule.domain.lineroute.RouteVariant;
import pl.krktransit.schedule.domain.schedule.ScheduleHash;

public interface ScheduleReader {
    List<RouteVariant> lineRouteVariants(String lineNumber);
    ScheduleHash currentHash(FeedId feed);
    TripContext tripContext(ScheduleHash hash, String tripId);
    ShapeContext shape(ScheduleHash hash, String shapeId);
}
