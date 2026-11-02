package pl.krktransit.publicapi.adapter.outbound.grpc;

import static java.util.stream.Collectors.toUnmodifiableMap;

import pl.krktransit.prediction.contracts.v1.PredictArrivalsRequest;
import pl.krktransit.prediction.contracts.v1.PredictArrivalsResponse;
import pl.krktransit.prediction.contracts.v1.PredictedArrival;
import pl.krktransit.publicapi.application.schedule.TripStop;
import pl.krktransit.publicapi.application.upcoming.PredictedDelays;
import pl.krktransit.publicapi.application.upcoming.PredictionRequest;

final class PredictionProtoMapper {
    PredictArrivalsRequest request(PredictionRequest request) {
        var message = PredictArrivalsRequest.newBuilder().setCityId(request.cityId()).setTripId(request.tripId())
                .setServiceDate(request.serviceDate().toString()).setLineNumber(request.lineNumber())
                .setMaxStopSequence(request.maximumSequence()).addAllTargetSequences(request.targetSequences());
        request.stops().forEach(stop -> message.addStops(stop(stop)));
        return message.build();
    }

    PredictedDelays delays(PredictArrivalsResponse response) {
        return new PredictedDelays(response.getPredictionsList().stream().filter(PredictedArrival::hasPredictedDelaySeconds)
                .collect(toUnmodifiableMap(PredictedArrival::getStopSequence, PredictedArrival::getPredictedDelaySeconds)));
    }

    private pl.krktransit.prediction.contracts.v1.TripStop stop(TripStop stop) {
        var message = pl.krktransit.prediction.contracts.v1.TripStop.newBuilder().setStopSequence(stop.sequence())
                .setStopName(stop.stopName()).setArrivalSeconds(stop.arrivalSeconds());
        if (stop.stopDescription() != null) message.setStopDesc(stop.stopDescription());
        return message.build();
    }
}
