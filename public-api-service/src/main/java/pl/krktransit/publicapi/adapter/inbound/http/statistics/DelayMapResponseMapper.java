package pl.krktransit.publicapi.adapter.inbound.http.statistics;

import java.util.List;
import pl.krktransit.publicapi.adapter.inbound.http.json.BandDelayItem;
import pl.krktransit.publicapi.adapter.inbound.http.json.DelayMapDirectionItem;
import pl.krktransit.publicapi.adapter.inbound.http.json.DelayMapResponse;
import pl.krktransit.publicapi.adapter.inbound.http.json.DelayMapSegmentItem;
import pl.krktransit.publicapi.adapter.inbound.http.json.DelayMapStopItem;
import pl.krktransit.publicapi.application.delaymap.DelayMap;
import pl.krktransit.publicapi.application.delaymap.DelayMapDirection;
import pl.krktransit.publicapi.application.delaymap.DelayMapRouteSegment;
import pl.krktransit.publicapi.application.schedule.RouteStop;
import pl.krktransit.publicapi.application.statistics.StatisticsQuery;

final class DelayMapResponseMapper {
    DelayMapResponse response(StatisticsQuery query, DelayMap map) {
        return new DelayMapResponse(query.lineNumber(), query.startDate().toString(), query.endDate().toString(),
                map.directions().stream().map(this::direction).toList());
    }

    private DelayMapDirectionItem direction(DelayMapDirection direction) {
        return new DelayMapDirectionItem(direction.headsign(), direction.tripsCount(),
                direction.stops().stream().map(this::stop).toList(), direction.segments().stream().map(this::segment).toList());
    }

    private DelayMapStopItem stop(RouteStop stop) {
        return new DelayMapStopItem(stop.stopKey(), stop.stopName(), stop.stopDesc(), stop.latitude(), stop.longitude());
    }

    private DelayMapSegmentItem segment(DelayMapRouteSegment segment) {
        return new DelayMapSegmentItem(segment.fromStopKey(), segment.toStopKey(),
                segment.points().stream().map(point -> List.of(point.latitude(), point.longitude())).toList(),
                segment.delays().stream().map(delay -> new BandDelayItem(delay.band().name(), delay.samples(),
                        delay.meanSeconds(), delay.medianSeconds(), delay.p90Seconds())).toList());
    }
}
