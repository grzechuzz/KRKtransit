package pl.krktransit.schedule.domain.lineroute;

import java.util.List;
import java.util.Map;

public record RouteVariant(List<RouteStop> stops, long tripsCount, Map<String, Long> headsignCounts,
                           List<RoutePoint> points) {
    public RouteVariant {
        stops = List.copyOf(stops);
        headsignCounts = Map.copyOf(headsignCounts);
        points = List.copyOf(points);
    }

    public List<StopKey> stopKeys() { return stops.stream().map(RouteStop::key).toList(); }
}
