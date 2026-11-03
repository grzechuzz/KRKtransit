package pl.krktransit.publicapi.application.delaymap;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import pl.krktransit.publicapi.application.schedule.LineDirection;
import pl.krktransit.publicapi.application.schedule.RouteSegment;
import pl.krktransit.publicapi.application.schedule.ScheduleSource;
import pl.krktransit.publicapi.application.statistics.BandDelay;
import pl.krktransit.publicapi.application.statistics.StatisticsQuery;
import pl.krktransit.publicapi.application.statistics.StatisticsSource;

public final class LineDelayMap {
    private final ScheduleSource schedule;
    private final StatisticsSource statistics;

    public LineDelayMap(ScheduleSource schedule, StatisticsSource statistics) {
        this.schedule = schedule;
        this.statistics = statistics;
    }

    public DelayMap get(StatisticsQuery query) {
        var routes = schedule.lineRoutes(query.lineNumber());
        var delays = statistics.delayMap(query);
        var bySegment = new HashMap<SegmentKey, List<BandDelay>>();
        delays.segments().forEach(segment -> bySegment.put(new SegmentKey(segment.fromStopKey(), segment.toStopKey()), segment.delays()));
        return new DelayMap(routes.directions().stream().map(direction -> direction(direction, bySegment)).toList());
    }

    private DelayMapDirection direction(LineDirection direction, Map<SegmentKey, List<BandDelay>> delays) {
        return new DelayMapDirection(direction.headsign(), direction.tripsCount(), direction.stops(),
                direction.segments().stream().map(segment -> segment(segment, delays)).toList());
    }

    private DelayMapRouteSegment segment(RouteSegment segment, Map<SegmentKey, List<BandDelay>> delays) {
        var key = new SegmentKey(segment.fromStopKey(), segment.toStopKey());
        return new DelayMapRouteSegment(segment.fromStopKey(), segment.toStopKey(), segment.points(),
                delays.getOrDefault(key, List.of()));
    }

    private record SegmentKey(String from, String to) {}
}
