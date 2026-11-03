package pl.krktransit.schedule.domain.lineroute;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

public final class LineDirections {
    private static final int MINIMUM_OVERLAP_DENOMINATOR = 2;
    private static final Comparator<RouteVariant> VARIANT_ORDER = Comparator.comparingLong(RouteVariant::tripsCount).reversed()
            .thenComparing(RouteVariant::stopKeys, LineDirections::compareKeys);

    private LineDirections() {}

    public static List<LineDirection> from(List<RouteVariant> variants) {
        var groups = new ArrayList<Direction>();
        for (var variant : mergeVariants(variants)) {
            var keys = segmentKeys(variant);
            if (keys.isEmpty()) continue;
            Direction best = null;
            int largestOverlap = -1;
            for (var direction : groups) {
                int overlap = direction.overlap(keys);
                if (accepts(keys, overlap) && overlap > largestOverlap) {
                    best = direction;
                    largestOverlap = overlap;
                }
            }
            if (best == null) {
                best = new Direction(keys);
                groups.add(best);
            }
            best.add(variant);
        }
        return groups.stream().map(Direction::result)
                .sorted(Comparator.comparingLong(LineDirection::tripsCount).reversed()).toList();
    }

    private static boolean accepts(Set<SegmentKey> keys, int overlap) {
        return (long) overlap * MINIMUM_OVERLAP_DENOMINATOR >= keys.size();
    }

    private static List<RouteVariant> mergeVariants(List<RouteVariant> variants) {
        var merged = new LinkedHashMap<List<StopKey>, RouteVariant>();
        for (var variant : variants.stream().sorted(VARIANT_ORDER).toList()) {
            merged.merge(variant.stopKeys(), variant, LineDirections::merge);
        }
        return merged.values().stream().sorted(VARIANT_ORDER).toList();
    }

    private static RouteVariant merge(RouteVariant first, RouteVariant next) {
        var counts = new HashMap<>(first.headsignCounts());
        next.headsignCounts().forEach((name, count) -> counts.merge(name, count, Long::sum));
        return new RouteVariant(first.stops(), first.tripsCount() + next.tripsCount(), counts, first.points());
    }

    private static int compareKeys(List<StopKey> first, List<StopKey> second) {
        for (int index = 0; index < Math.min(first.size(), second.size()); index++) {
            int compared = first.get(index).value().compareTo(second.get(index).value());
            if (compared != 0) return compared;
        }
        return Integer.compare(first.size(), second.size());
    }

    private static Set<SegmentKey> segmentKeys(RouteVariant variant) {
        var keys = new LinkedHashSet<SegmentKey>();
        for (int index = 1; index < variant.stops().size(); index++) {
            keys.add(new SegmentKey(variant.stops().get(index - 1).key(), variant.stops().get(index).key()));
        }
        return keys;
    }

    private static final class Direction {
        private final Set<SegmentKey> pattern;
        private final Map<String, Long> headsignCounts = new HashMap<>();
        private final Map<StopKey, RouteStop> stops = new LinkedHashMap<>();
        private final Map<SegmentKey, RouteSegment> segments = new LinkedHashMap<>();
        private long tripsCount;

        private Direction(Set<SegmentKey> pattern) { this.pattern = Set.copyOf(pattern); }

        private int overlap(Set<SegmentKey> keys) { return (int) keys.stream().filter(pattern::contains).count(); }

        private void add(RouteVariant variant) {
            tripsCount += variant.tripsCount();
            variant.headsignCounts().forEach((name, count) -> headsignCounts.merge(name, count, Long::sum));
            variant.stops().forEach(stop -> stops.putIfAbsent(stop.key(), stop));
            new ShapeCutter().cut(variant.stops(), variant.points()).forEach(segment -> segments.putIfAbsent(segment.key(), segment));
        }

        private LineDirection result() {
            var name = headsignCounts.entrySet().stream()
                    .sorted(Map.Entry.<String, Long>comparingByValue().reversed().thenComparing(Map.Entry.comparingByKey()))
                    .map(Map.Entry::getKey).findFirst().orElse("");
            return new LineDirection(name, tripsCount, List.copyOf(stops.values()), List.copyOf(segments.values()));
        }
    }
}
