package pl.krktransit.schedule.domain.lineroute;

import static org.junit.jupiter.api.Assertions.*;

import java.util.List;
import org.junit.jupiter.api.Test;

class ShapeCutterTest {
    private final ShapeCutter cutter = new ShapeCutter();

    @Test
    void cutsAtTheNearestPointsAndSharesExactBoundaryPoints() {
        var points = List.of(point(0), point(0.01), point(0.02), point(0.03));
        var result = cutter.cut(List.of(stop("A", 0.001), stop("B", 0.019), stop("C", 0.031)), points);
        assertEquals(2, result.size());
        assertEquals(List.of(point(0), point(0.01), point(0.02)), result.getFirst().points());
        assertEquals(List.of(point(0.02), point(0.03)), result.getLast().points());
    }

    @Test
    void placesTheBoundaryAtTheNearestPointAndNotAtTheFirstOneCloseEnough() {
        var approach = List.of(point(0), point(0.0002), point(0.0004), point(0.0006), point(0.0008), point(0.001), point(0.002));
        var result = cutter.cut(List.of(stop("A", 0), stop("B", 0.001), stop("C", 0.002)), approach);
        assertEquals(point(0.001), result.getFirst().points().getLast());
        assertEquals(point(0.001), result.getLast().points().getFirst());
    }

    @Test
    void staysOnTheEarlierPassWhenALoopReturnsToTheSameCoordinates() {
        var points = List.of(point(0), point(0.01), new RoutePoint(50.01, 0.01), new RoutePoint(50.01, 0), point(0));
        var stops = List.of(stop("A", 0), stop("B", 0.01),
                new RouteStop(StopKey.of("C", ""), "C", "", 50.01, 0), stop("A", 0));
        var result = cutter.cut(stops, points);
        assertEquals(3, result.size());
        assertEquals(List.of(point(0), point(0.01)), result.getFirst().points());
        assertEquals(List.of(new RoutePoint(50.01, 0), point(0)), result.getLast().points());
    }

    @Test
    void takesTheLaterPassWhenTheLineEndsCloserToTheStop() {
        var nearby = new RoutePoint(50.0002, 0.01);
        var points = List.of(point(0), nearby, new RoutePoint(50.01, 0.01), point(0.01));
        var result = cutter.cut(List.of(stop("A", 0), stop("B", 0.01)), points);
        assertEquals(points, result.getFirst().points());
    }

    @Test
    void returnsASinglePointSegmentWhenConsecutiveStopsShareTheirNearestPoint() {
        var same = List.of(point(0), point(0), point(0));
        var result = cutter.cut(List.of(stop("A", 1), stop("B", -1), stop("C", 0)), same);
        assertEquals(2, result.size());
        result.forEach(segment -> assertEquals(List.of(point(0)), segment.points()));
    }

    @Test
    void neverMovesBackwardsWhenTheNextStopIsBehindThePreviousOne() {
        var result = cutter.cut(List.of(stop("A", 0.008), stop("B", 0.002)), List.of(point(0), point(0.01)));
        assertEquals(List.of(point(0.01)), result.getFirst().points());
    }

    @Test
    void returnsNothingWithoutEnoughStopsOrPoints() {
        assertTrue(cutter.cut(List.of(), List.of()).isEmpty());
        assertTrue(cutter.cut(List.of(stop("A", 0)), List.of(point(0), point(0.01))).isEmpty());
        assertTrue(cutter.cut(List.of(stop("A", 0), stop("B", 0.01)), List.of(point(0))).isEmpty());
    }

    private static RoutePoint point(double longitude) { return new RoutePoint(50, longitude); }
    private static RouteStop stop(String name, double longitude) {
        return new RouteStop(StopKey.of(name, ""), name, "", 50, longitude);
    }
}
