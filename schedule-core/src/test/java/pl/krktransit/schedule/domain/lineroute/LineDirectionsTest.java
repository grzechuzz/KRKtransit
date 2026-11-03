package pl.krktransit.schedule.domain.lineroute;

import static org.junit.jupiter.api.Assertions.*;

import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class LineDirectionsTest {
    @Test
    void groupsShortenedTripsAndKeepsReverseDirectionSeparate() {
        var result = LineDirections.from(List.of(variant("ABCD", 100, Map.of("D", 100L)),
                variant("BC", 10, Map.of("C", 10L)), variant("DCBA", 80, Map.of("A", 80L))));
        assertEquals(2, result.size());
        assertEquals(110, result.getFirst().tripsCount());
        assertEquals("D", result.getFirst().headsign());
        assertEquals(3, result.getFirst().segments().size());
        assertEquals(80, result.getLast().tripsCount());
    }

    @Test
    void doesNotGrowThePatternThroughAChainOfOverlappingVariants() {
        var result = LineDirections.from(List.of(variant("ABCD", 100, Map.of()), variant("BCDE", 50, Map.of()),
                variant("DEFG", 20, Map.of())));
        assertEquals(2, result.size());
        assertEquals(150, result.getFirst().tripsCount());
    }

    @Test
    void sumsAllHeadsignCountsRatherThanVotingForTheWinningNameOfEachVariant() {
        var result = LineDirections.from(List.of(variant("ABCD", 100, Map.of("X", 51L, "Y", 49L)),
                variant("BCD", 90, Map.of("Y", 46L, "Z", 44L))));
        assertEquals("Y", result.getFirst().headsign());
        assertEquals(190, result.getFirst().tripsCount());
    }

    @Test
    void mergesIdenticalStopSequencesAcrossFeedsAndUsesTheMostFrequentGeometry() {
        var frequent = variant("ABC", 20, Map.of("C", 20L));
        var other = new RouteVariant(frequent.stops(), 10, Map.of("Other", 10L),
                List.of(new RoutePoint(51, 19), new RoutePoint(51, 20)));
        var result = LineDirections.from(List.of(other, frequent));
        assertEquals(1, result.size());
        assertEquals(30, result.getFirst().tripsCount());
        assertEquals(50, result.getFirst().segments().getFirst().points().getFirst().latitude());
    }

    @Test
    void acceptsExactlyHalfAndResolvesTiesDeterministically() {
        var first = variant("ABC", 20, Map.of("Z", 10L, "A", 10L));
        var second = variant("CBA", 20, Map.of("Reverse", 20L));
        var half = variant("ABD", 1, Map.of());
        assertEquals(LineDirections.from(List.of(first, second, half)), LineDirections.from(List.of(half, second, first)));
        assertEquals(21, LineDirections.from(List.of(first, second, half)).getFirst().tripsCount());
        assertEquals("A", LineDirections.from(List.of(first)).getFirst().headsign());
    }

    @Test
    void supportsASingleLoopDirectionAndEmptyInput() {
        assertEquals(1, LineDirections.from(List.of(variant("ABCA", 5, Map.of()))).size());
        assertTrue(LineDirections.from(List.of()).isEmpty());
    }

    private static RouteVariant variant(String names, long count, Map<String, Long> headsigns) {
        var stops = names.chars().mapToObj(letter -> {
            String name = Character.toString(letter);
            return new RouteStop(StopKey.of(name, ""), name, "", 50, 19 + (letter - 'A') * 0.01);
        }).toList();
        return new RouteVariant(stops, count, headsigns,
                stops.stream().map(stop -> new RoutePoint(stop.latitude(), stop.longitude())).toList());
    }
}
