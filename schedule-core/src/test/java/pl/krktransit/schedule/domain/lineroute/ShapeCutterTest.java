package pl.krktransit.schedule.domain.lineroute;

import static org.junit.jupiter.api.Assertions.*;

import java.util.List;
import org.junit.jupiter.api.Test;

class ShapeCutterTest {
    private final ShapeCutter cutter = new ShapeCutter();

    @Test
    void cutsInsideEdgesAndSharesExactBoundaryPoints() {
        var points = List.of(point(0), point(0.01), point(0.02));
        var result = cutter.cut(List.of(stop("A", 0.002), stop("B", 0.008), stop("C", 0.018)), points);
        assertEquals(2, result.size());
        assertEquals(result.getFirst().points().getLast(), result.getLast().points().getFirst());
        assertEquals(0.002, result.getFirst().points().getFirst().longitude(), 1e-10);
        assertEquals(0.018, result.getLast().points().getLast().longitude(), 1e-10);
        assertTrue(result.getLast().points().contains(point(0.01)));
    }

    @Test
    void followsLoopOccurrencesInsteadOfJumpingToTheLastVisit() {
        var points = List.of(point(0), point(0.01), new RoutePoint(50.01, 0.01), new RoutePoint(50.01, 0), point(0));
        var stops = List.of(stop("A", 0), stop("B", 0.01),
                new RouteStop(StopKey.of("C", ""), "C", "", 50.01, 0), stop("A", 0));
        var result = cutter.cut(stops, points);
        assertEquals(3, result.size());
        assertEquals(point(0), result.getFirst().points().getFirst());
        assertTrue(result.getLast().points().size() >= 2);
        assertEquals(point(0), result.getLast().points().getLast());
    }

    @Test
    void toleratesDistantStopsRepeatedPointsAndCoincidentProjections() {
        var same = List.of(point(0), point(0), point(0));
        var result = cutter.cut(List.of(stop("A", 1), stop("B", -1), stop("C", 0)), same);
        assertEquals(2, result.size());
        result.forEach(segment -> {
            assertEquals(2, segment.points().size());
            segment.points().forEach(p -> assertTrue(Double.isFinite(p.latitude()) && Double.isFinite(p.longitude())));
        });
    }

    @Test
    void neverMovesBackwardsWhenTheNextStopIsBehindThePreviousOne() {
        var result = cutter.cut(List.of(stop("A", 0.008), stop("B", 0.002)), List.of(point(0), point(0.01)));
        assertEquals(result.getFirst().points().getFirst(), result.getFirst().points().getLast());
        assertTrue(cutter.cut(List.of(), List.of()).isEmpty());
    }

    private static RoutePoint point(double longitude) { return new RoutePoint(50, longitude); }
    private static RouteStop stop(String name, double longitude) {
        return new RouteStop(StopKey.of(name, ""), name, "", 50, longitude);
    }
}
