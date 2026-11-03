package pl.krktransit.stopevents.adapter.inbound.grpc;

import com.google.protobuf.Timestamp;
import java.time.Instant;
import pl.krktransit.stopevents.contracts.v1.*;
import pl.krktransit.stopevents.domain.statistics.StatisticsResult;

final class StatisticsResponseMapper {
    MaxDelayResponse maxDelay(StatisticsRequest query, StatisticsResult.MaxDelay result) {
        var response = MaxDelayResponse.newBuilder().setQuery(query).setTripsAnalyzed(result.tripsAnalyzed());
        result.segments().forEach(segment -> response.addMaxDelay(segment(segment)));
        return response.build();
    }

    RouteDelayResponse routeDelay(StatisticsRequest query, StatisticsResult.RouteDelay result) {
        var response = RouteDelayResponse.newBuilder().setQuery(query).setTripsAnalyzed(result.tripsAnalyzed());
        result.routes().forEach(route -> response.addMaxRouteDelay(route(route)));
        return response.build();
    }

    PunctualityResponse punctuality(StatisticsRequest query, StatisticsResult.Punctuality result) {
        return PunctualityResponse.newBuilder().setQuery(query).setTotalStops(result.total())
                .setOnTimeCount(result.onTime()).setOnTimePercent(result.onTimePercent())
                .setSlightlyDelayedCount(result.slightlyDelayed()).setSlightlyDelayedPercent(result.slightlyDelayedPercent())
                .setDelayedCount(result.delayed()).setDelayedPercent(result.delayedPercent()).build();
    }

    TrendResponse trend(StatisticsRequest query, StatisticsResult.Trend result) {
        var response = TrendResponse.newBuilder().setQuery(query);
        result.days().forEach(day -> response.addDays(TrendDay.newBuilder().setDate(day.date().toString())
                .setAvgDelaySeconds(day.averageDelaySeconds()).setTripsCount(day.tripsCount())));
        return response.build();
    }

    private SegmentDelay segment(StatisticsResult.Segment row) {
        return SegmentDelay.newBuilder().setTripId(row.tripId()).setServiceDate(row.serviceDate().toString())
                .setLineNumber(row.lineNumber()).setVehicleNumber(row.vehicleNumber())
                .setFromStop(row.fromStop()).setToStop(row.toStop()).setFromSequence(row.fromSequence())
                .setToSequence(row.toSequence()).setFromPlannedTime(timestamp(row.fromPlannedTime()))
                .setFromEventTime(timestamp(row.fromEventTime())).setToPlannedTime(timestamp(row.toPlannedTime()))
                .setToEventTime(timestamp(row.toEventTime())).setDelayGeneratedSeconds(row.delayGeneratedSeconds())
                .setHeadsign(row.headsign()).setIsEstimated(row.estimated()).build();
    }

    private RouteDelay route(StatisticsResult.Route row) {
        return RouteDelay.newBuilder().setTripId(row.tripId()).setServiceDate(row.serviceDate().toString())
                .setLineNumber(row.lineNumber()).setVehicleNumber(row.vehicleNumber())
                .setFirstStop(row.firstStop()).setLastStop(row.lastStop())
                .setFirstPlannedTime(timestamp(row.firstPlannedTime())).setFirstEventTime(timestamp(row.firstEventTime()))
                .setLastPlannedTime(timestamp(row.lastPlannedTime())).setLastEventTime(timestamp(row.lastEventTime()))
                .setStartDelaySeconds(row.startDelaySeconds()).setEndDelaySeconds(row.endDelaySeconds())
                .setDelayGeneratedSeconds(row.delayGeneratedSeconds()).setHeadsign(row.headsign())
                .setIsEstimated(row.estimated()).build();
    }

    private Timestamp timestamp(Instant time) {
        return Timestamp.newBuilder().setSeconds(time.getEpochSecond()).setNanos(time.getNano()).build();
    }
}
