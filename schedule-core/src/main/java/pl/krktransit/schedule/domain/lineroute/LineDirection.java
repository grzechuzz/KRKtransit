package pl.krktransit.schedule.domain.lineroute;

import java.util.List;

public record LineDirection(String headsign, long tripsCount, List<RouteStop> stops, List<RouteSegment> segments) {
    public LineDirection {
        stops = List.copyOf(stops);
        segments = List.copyOf(segments);
    }
}
