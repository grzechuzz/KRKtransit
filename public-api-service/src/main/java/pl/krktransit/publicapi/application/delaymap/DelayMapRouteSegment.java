package pl.krktransit.publicapi.application.delaymap;

import java.util.List;
import pl.krktransit.publicapi.application.schedule.RoutePoint;
import pl.krktransit.publicapi.application.statistics.BandDelay;

public record DelayMapRouteSegment(String fromStopKey, String toStopKey, List<RoutePoint> points, List<BandDelay> delays) {
    public DelayMapRouteSegment { points = List.copyOf(points); delays = List.copyOf(delays); }
}
