package pl.krktransit.schedule.domain.lineroute;

import java.util.List;

public record RouteSegment(SegmentKey key, List<RoutePoint> points) {
    public RouteSegment { points = List.copyOf(points); }
}
