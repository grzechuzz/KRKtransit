package pl.krktransit.publicapi.adapter.outbound.grpc;

import java.time.Instant;
import pl.krktransit.livevehicles.contracts.v1.GetTripForecastResponse;
import pl.krktransit.livevehicles.contracts.v1.StopForecast;
import pl.krktransit.publicapi.application.upcoming.OperatorForecast;

final class OperatorForecastProtoMapper {
    OperatorForecast forecast(GetTripForecastResponse response) {
        return new OperatorForecast(response.getStopsList().stream().filter(StopForecast::hasArrivalTime).map(this::stop).toList());
    }

    private OperatorForecast.ForecastedStop stop(StopForecast stop) {
        var arrival = stop.getArrivalTime();
        return new OperatorForecast.ForecastedStop(stop.hasStopSequence() ? stop.getStopSequence() : null,
                stop.hasStopId() ? stop.getStopId() : null, Instant.ofEpochSecond(arrival.getSeconds(), arrival.getNanos()));
    }
}
