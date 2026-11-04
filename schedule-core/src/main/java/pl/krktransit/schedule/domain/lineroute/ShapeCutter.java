package pl.krktransit.schedule.domain.lineroute;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.stream.IntStream;

public final class ShapeCutter {
    private static final double EARTH_RADIUS_METRES = 6_371_000;

    public List<RouteSegment> cut(List<RouteStop> stops, List<RoutePoint> points) {
        if (stops.size() < 2 || points.size() < 2) return List.of();
        var segments = new ArrayList<RouteSegment>();
        int previous = nearestPoint(stops.getFirst(), points, 0);
        for (int index = 1; index < stops.size(); index++) {
            int next = nearestPoint(stops.get(index), points, previous);
            segments.add(new RouteSegment(new SegmentKey(stops.get(index - 1).key(), stops.get(index).key()),
                    points.subList(previous, next + 1)));
            previous = next;
        }
        return List.copyOf(segments);
    }

    private static int nearestPoint(RouteStop stop, List<RoutePoint> points, int from) {
        return IntStream.range(from, points.size()).boxed()
                .min(Comparator.comparingDouble(index -> distance(stop, points.get(index))))
                .orElseThrow();
    }

    private static double distance(RouteStop stop, RoutePoint point) {
        double x = Math.toRadians(point.longitude() - stop.longitude()) * Math.cos(Math.toRadians(stop.latitude()));
        double y = Math.toRadians(point.latitude() - stop.latitude());
        return Math.hypot(x, y) * EARTH_RADIUS_METRES;
    }
}
