package pl.krktransit.publicapi.application.delaymap;

import java.util.List;
import pl.krktransit.publicapi.application.schedule.RouteStop;

public record DelayMapDirection(String headsign, long tripsCount, List<RouteStop> stops, List<DelayMapRouteSegment> segments) {
    public DelayMapDirection { stops = List.copyOf(stops); segments = List.copyOf(segments); }
}
