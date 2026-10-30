package pl.krktransit.publicapi.adapter.outbound.grpc;

import static pl.krktransit.publicapi.adapter.outbound.grpc.ProtoTimes.instant;

import java.time.LocalDate;
import pl.krktransit.publicapi.application.statistics.MaxDelayRanking;
import pl.krktransit.publicapi.application.statistics.Punctuality;
import pl.krktransit.publicapi.application.statistics.RouteDelay;
import pl.krktransit.publicapi.application.statistics.RouteDelayRanking;
import pl.krktransit.publicapi.application.statistics.SegmentDelay;
import pl.krktransit.publicapi.application.statistics.StatisticsQuery;
import pl.krktransit.publicapi.application.statistics.Trend;
import pl.krktransit.publicapi.application.statistics.TrendDay;
import pl.krktransit.stopevents.contracts.v1.MaxDelayResponse;
import pl.krktransit.stopevents.contracts.v1.PunctualityResponse;
import pl.krktransit.stopevents.contracts.v1.RouteDelayResponse;
import pl.krktransit.stopevents.contracts.v1.StatisticsRequest;
import pl.krktransit.stopevents.contracts.v1.TrendResponse;

final class StatisticsProtoMapper {
    StatisticsRequest request(StatisticsQuery query) {
        return StatisticsRequest.newBuilder().setLineNumber(query.lineNumber()).setStartDate(query.startDate().toString())
                .setEndDate(query.endDate().toString()).setIncludeEstimated(query.includeEstimated()).build();
    }

    MaxDelayRanking maxDelay(MaxDelayResponse response) {
        return new MaxDelayRanking(response.getMaxDelayList().stream().map(this::segment).toList(), response.getTripsAnalyzed());
    }

    RouteDelayRanking routeDelay(RouteDelayResponse response) {
        return new RouteDelayRanking(response.getMaxRouteDelayList().stream().map(this::route).toList(),
                response.getTripsAnalyzed());
    }

    Punctuality punctuality(PunctualityResponse response) {
        return new Punctuality(response.getTotalStops(), response.getOnTimeCount(), response.getOnTimePercent(),
                response.getSlightlyDelayedCount(), response.getSlightlyDelayedPercent(), response.getDelayedCount(),
                response.getDelayedPercent());
    }

    Trend trend(TrendResponse response) {
        return new Trend(response.getDaysList().stream().map(this::day).toList());
    }

    private SegmentDelay segment(pl.krktransit.stopevents.contracts.v1.SegmentDelay row) {
        return new SegmentDelay(row.getTripId(), LocalDate.parse(row.getServiceDate()), row.getLineNumber(),
                row.getVehicleNumber(), row.getFromStop(), row.getToStop(), row.getFromSequence(), row.getToSequence(),
                instant(row.getFromPlannedTime()), instant(row.getFromEventTime()), instant(row.getToPlannedTime()),
                instant(row.getToEventTime()), row.getDelayGeneratedSeconds(), row.getHeadsign(), row.getIsEstimated());
    }

    private RouteDelay route(pl.krktransit.stopevents.contracts.v1.RouteDelay row) {
        return new RouteDelay(row.getTripId(), LocalDate.parse(row.getServiceDate()), row.getLineNumber(),
                row.getVehicleNumber(), row.getFirstStop(), row.getLastStop(), instant(row.getFirstPlannedTime()),
                instant(row.getFirstEventTime()), instant(row.getLastPlannedTime()), instant(row.getLastEventTime()),
                row.getStartDelaySeconds(), row.getEndDelaySeconds(), row.getDelayGeneratedSeconds(), row.getHeadsign(),
                row.getIsEstimated());
    }

    private TrendDay day(pl.krktransit.stopevents.contracts.v1.TrendDay day) {
        return new TrendDay(LocalDate.parse(day.getDate()), day.getAvgDelaySeconds(), day.getTripsCount());
    }
}
