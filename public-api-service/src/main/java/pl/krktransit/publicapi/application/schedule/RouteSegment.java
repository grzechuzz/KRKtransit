package pl.krktransit.publicapi.application.schedule;

import java.util.List;

public record RouteSegment(String fromStopKey, String toStopKey, List<RoutePoint> points) {
    public RouteSegment { points = List.copyOf(points); }
}
