package pl.krktransit.schedule.domain.lineroute;

import java.util.ArrayList;
import java.util.List;

public final class ShapeCutter {
    private static final double EARTH_RADIUS_METRES = 6_371_000;
    private static final double PROJECTION_TOLERANCE_METRES = 30;

    public List<RouteSegment> cut(List<RouteStop> stops, List<RoutePoint> points) {
        if (stops.size() < 2 || points.size() < 2) return List.of();
        var geometry = new Geometry(points);
        var segments = new ArrayList<RouteSegment>();
        var previous = geometry.project(stops.getFirst(), 0);
        for (int index = 1; index < stops.size(); index++) {
            var next = geometry.project(stops.get(index), previous.position());
            segments.add(new RouteSegment(new SegmentKey(stops.get(index - 1).key(), stops.get(index).key()),
                    geometry.between(previous, next)));
            previous = next;
        }
        return List.copyOf(segments);
    }

    private record Projection(double position, RoutePoint point, double distance) {}
    private record Metres(double x, double y) {}

    private static final class Geometry {
        private final List<RoutePoint> points;
        private final List<Metres> projected;
        private final double[] lengths;
        private final double longitudeScale;
        private final RoutePoint origin;

        private Geometry(List<RoutePoint> points) {
            this.points = points;
            origin = points.getFirst();
            longitudeScale = Math.cos(Math.toRadians(origin.latitude()));
            projected = points.stream().map(this::metres).toList();
            lengths = new double[points.size()];
            for (int index = 1; index < points.size(); index++) {
                lengths[index] = lengths[index - 1] + distance(projected.get(index - 1), projected.get(index));
            }
        }

        private Metres metres(RoutePoint point) {
            return new Metres(Math.toRadians(point.longitude() - origin.longitude()) * EARTH_RADIUS_METRES * longitudeScale,
                    Math.toRadians(point.latitude() - origin.latitude()) * EARTH_RADIUS_METRES);
        }

        private Projection project(RouteStop stop, double minimum) {
            var target = metres(new RoutePoint(stop.latitude(), stop.longitude()));
            var candidates = new ArrayList<Projection>();
            double best = Double.POSITIVE_INFINITY;
            for (int index = 1; index < points.size(); index++) {
                if (lengths[index] < minimum) continue;
                var candidate = projection(index, target, minimum);
                candidates.add(candidate);
                best = Math.min(best, candidate.distance());
            }
            for (var candidate : candidates) {
                if (candidate.distance() <= best + PROJECTION_TOLERANCE_METRES) return candidate;
            }
            return new Projection(lengths[lengths.length - 1], points.getLast(), distance(target, projected.getLast()));
        }

        private Projection projection(int index, Metres target, double minimum) {
            var start = projected.get(index - 1);
            var end = projected.get(index);
            double dx = end.x() - start.x();
            double dy = end.y() - start.y();
            double squared = dx * dx + dy * dy;
            double length = lengths[index] - lengths[index - 1];
            double lower = length == 0 ? 0 : Math.max(0, (minimum - lengths[index - 1]) / length);
            double fraction = squared == 0 ? 0 : ((target.x() - start.x()) * dx + (target.y() - start.y()) * dy) / squared;
            fraction = Math.clamp(fraction, lower, 1);
            var sourceStart = points.get(index - 1);
            var sourceEnd = points.get(index);
            var point = new RoutePoint(sourceStart.latitude() + fraction * (sourceEnd.latitude() - sourceStart.latitude()),
                    sourceStart.longitude() + fraction * (sourceEnd.longitude() - sourceStart.longitude()));
            return new Projection(Math.max(minimum, lengths[index - 1] + fraction * length), point,
                    distance(target, new Metres(start.x() + fraction * dx, start.y() + fraction * dy)));
        }

        private List<RoutePoint> between(Projection from, Projection to) {
            var result = new ArrayList<RoutePoint>();
            result.add(from.point());
            for (int index = 0; index < points.size(); index++) {
                if (lengths[index] > from.position() && lengths[index] < to.position()) result.add(points.get(index));
            }
            result.add(to.point());
            return result;
        }

        private static double distance(Metres from, Metres to) { return Math.hypot(to.x() - from.x(), to.y() - from.y()); }
    }
}
