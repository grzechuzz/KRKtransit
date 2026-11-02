package pl.krktransit.publicapi.adapter.inbound.http.statistics;

import static pl.krktransit.publicapi.adapter.inbound.http.ResponseTimes.warsawLocal;

import pl.krktransit.publicapi.adapter.inbound.http.json.MaxDelayResponse;
import pl.krktransit.publicapi.adapter.inbound.http.json.PunctualityResponse;
import pl.krktransit.publicapi.adapter.inbound.http.json.RouteDelayItem;
import pl.krktransit.publicapi.adapter.inbound.http.json.RouteDelayResponse;
import pl.krktransit.publicapi.adapter.inbound.http.json.SegmentDelayItem;
import pl.krktransit.publicapi.adapter.inbound.http.json.TrendDayItem;
import pl.krktransit.publicapi.adapter.inbound.http.json.TrendResponse;
import pl.krktransit.publicapi.application.statistics.MaxDelayRanking;
import pl.krktransit.publicapi.application.statistics.Punctuality;
import pl.krktransit.publicapi.application.statistics.RouteDelay;
import pl.krktransit.publicapi.application.statistics.RouteDelayRanking;
import pl.krktransit.publicapi.application.statistics.SegmentDelay;
import pl.krktransit.publicapi.application.statistics.StatisticsQuery;
import pl.krktransit.publicapi.application.statistics.Trend;
import pl.krktransit.publicapi.application.statistics.TrendDay;

final class StatisticsResponseMapper {
    MaxDelayResponse maxDelay(StatisticsQuery query, MaxDelayRanking ranking) {
        return new MaxDelayResponse(query.lineNumber(), query.startDate().toString(), query.endDate().toString(),
                ranking.segments().stream().map(this::segment).toList(), ranking.tripsAnalyzed());
    }

    RouteDelayResponse routeDelay(StatisticsQuery query, RouteDelayRanking ranking) {
        return new RouteDelayResponse(query.lineNumber(), query.startDate().toString(), query.endDate().toString(),
                ranking.routes().stream().map(this::route).toList(), ranking.tripsAnalyzed());
    }

    PunctualityResponse punctuality(StatisticsQuery query, Punctuality result) {
        return new PunctualityResponse(query.lineNumber(), query.startDate().toString(), query.endDate().toString(),
                result.totalStops(), result.onTimeCount(), result.onTimePercent(), result.slightlyDelayedCount(),
                result.slightlyDelayedPercent(), result.delayedCount(), result.delayedPercent());
    }

    TrendResponse trend(StatisticsQuery query, Trend trend) {
        return new TrendResponse(query.lineNumber(), query.startDate().toString(), query.endDate().toString(),
                trend.days().stream().map(this::day).toList());
    }

    private SegmentDelayItem segment(SegmentDelay row) {
        return new SegmentDelayItem(row.tripId(), row.lineNumber(), row.vehicleNumber(), row.fromStop(), row.toStop(),
                row.fromSequence(), row.toSequence(), warsawLocal(row.fromPlannedTime()), warsawLocal(row.fromEventTime()),
                warsawLocal(row.toPlannedTime()), warsawLocal(row.toEventTime()), row.delayGeneratedSeconds(),
                row.headsign(), row.serviceDate().toString(), row.estimated());
    }

    private RouteDelayItem route(RouteDelay row) {
        return new RouteDelayItem(row.tripId(), row.lineNumber(), row.vehicleNumber(), row.firstStop(), row.lastStop(),
                warsawLocal(row.firstPlannedTime()), warsawLocal(row.firstEventTime()), warsawLocal(row.lastPlannedTime()),
                warsawLocal(row.lastEventTime()), row.startDelaySeconds(), row.endDelaySeconds(),
                row.delayGeneratedSeconds(), row.headsign(), row.serviceDate().toString(), row.estimated());
    }

    private TrendDayItem day(TrendDay day) {
        return new TrendDayItem(day.date().toString(), day.averageDelaySeconds(), day.tripsCount());
    }
}
